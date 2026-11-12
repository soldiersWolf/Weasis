/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;

/**
 * Keeps folders in sync with the explorer: the recent files of each watched folder are loaded at
 * start up and new files, also in new sub-folders such as one per day, are loaded as they appear.
 * A folder imported alone becomes watched; folders can also be added and removed from the sources
 * page.
 *
 * <p>The folders are polled rather than watched with a {@code WatchService}, whose events are not
 * reliable on network shares. Only the sub-folders modified within the time window are listed, so
 * a large archive is not walked on every poll, and a file is loaded once its size and modification
 * time stopped changing, so that a file still being copied is not read.
 */
public final class FolderWatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(FolderWatcher.class);

  /** Preference key: keep the imported folders watched. */
  public static final String P_ENABLED = "weasis.dicom.folder.watch"; // NON-NLS

  /** Preference key: how many days back the files are loaded and the sub-folders are listed. */
  public static final String P_DAYS = "weasis.dicom.folder.watch.days"; // NON-NLS

  private static final String P_ROOTS = "folder.watch.roots"; // NON-NLS
  private static final String P_LEGACY_ROOT = "folder.watch.root"; // NON-NLS
  private static final String SEPARATOR = "\u001f";
  private static final long POLL_SECONDS = 20;

  private static FolderWatcher instance;
  private static final List<Runnable> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

  private final BiConsumer<Path, List<File>> loader;
  private final Duration window;
  private final Map<Path, Root> roots = new LinkedHashMap<>();
  private ScheduledExecutorService scheduler;

  /** Files already handed to the loader and files whose copy may still be running. */
  private static final class Root {
    final Set<Path> known = new HashSet<>();
    final Map<Path, FileState> pending = new HashMap<>();
  }

  /** Size and modification time seen at the previous poll, to tell when a copy is finished. */
  private record FileState(long size, long modified) {}

  FolderWatcher(BiConsumer<Path, List<File>> loader, Duration window) {
    this.loader = loader;
    this.window = window;
  }

  public static boolean isEnabled() {
    return GuiUtils.getUICore().getSystemPreferences().getBooleanProperty(P_ENABLED, false);
  }

  /** Starts watching the remembered folders, loading their recent files into the model. */
  public static synchronized void start(DicomModel model) {
    if (instance != null || !isEnabled()) {
      return;
    }
    int days = GuiUtils.getUICore().getSystemPreferences().getIntProperty(P_DAYS, 7);
    instance =
        new FolderWatcher(
            (root, files) ->
                DicomModel.LOADING_EXECUTOR.execute(
                    new LoadLocalDicom(files.toArray(File[]::new), false, model, OpeningViewer.NONE)
                        .withSourceLabel(labelOf(root))),
            Duration.ofDays(Math.max(1, days)));
    instance.startPolling();
    List<Path> saved = savedFolders();
    FolderWatcher watcher = instance;
    // Listing a network share can be slow: never on the event dispatch thread
    watcher.scheduler.execute(() -> saved.forEach(f -> watcher.addRoot(f, true)));
  }

  private static List<Path> savedFolders() {
    var props = LocalPersistence.getProperties();
    String value = props.getProperty(P_ROOTS);
    if (value == null) {
      value = props.getProperty(P_LEGACY_ROOT, "");
    }
    List<Path> folders = new ArrayList<>();
    for (String s : value.split(SEPARATOR)) {
      if (!s.isBlank()) {
        folders.add(Path.of(s));
      }
    }
    return folders;
  }

  private static void saveFolders(List<Path> folders) {
    LocalPersistence.getProperties()
        .setProperty(
            P_ROOTS, String.join(SEPARATOR, folders.stream().map(Path::toString).toList()));
    CHANGE_LISTENERS.forEach(Runnable::run);
  }

  /**
   * Watches a folder the user imported. Its current files are being loaded by that import, so only
   * the files added afterwards are loaded by the watcher.
   */
  public static void watchImported(File folder) {
    watch(folder, false);
  }

  /** Watches a folder added on the sources page and loads its recent files. */
  public static void addFolder(File folder) {
    watch(folder, true);
  }

  private static synchronized void watch(File folder, boolean loadRecent) {
    if (instance == null || folder == null || !folder.isDirectory()) {
      return;
    }
    Path path = folder.toPath().toAbsolutePath().normalize();
    List<Path> folders = getWatchedFolders();
    if (!folders.contains(path)) {
      List<Path> updated = new ArrayList<>(folders);
      updated.add(path);
      saveFolders(updated);
    }
    FolderWatcher watcher = instance;
    watcher.scheduler.execute(() -> watcher.addRoot(path, loadRecent));
  }

  /** Stops watching a folder; the studies already loaded stay in the explorer. */
  public static synchronized void removeFolder(Path folder) {
    if (instance == null) {
      return;
    }
    List<Path> updated = new ArrayList<>(getWatchedFolders());
    if (updated.remove(folder)) {
      saveFolders(updated);
    }
    FolderWatcher watcher = instance;
    watcher.scheduler.execute(() -> watcher.removeRoot(folder));
  }

  /** Loads at once the files that appeared in the watched folders. */
  public static void pollNow() {
    FolderWatcher watcher = instance;
    if (watcher != null) {
      watcher.scheduler.execute(watcher::pollSafely);
    }
  }

  /** The watched folders, in the order they were added. */
  public static List<Path> getWatchedFolders() {
    return instance == null ? List.of() : savedFolders();
  }

  /** Runs the listener on the calling thread whenever the watched folders change. */
  public static void addChangeListener(Runnable listener) {
    CHANGE_LISTENERS.add(listener);
  }

  /** Name shown as the source of the studies loaded from the folder. */
  public static String labelOf(Path folder) {
    Path name = folder.getFileName();
    return name == null ? folder.toString() : name.toString();
  }

  synchronized void addRoot(Path folder, boolean loadRecent) {
    if (roots.containsKey(folder)) {
      return;
    }
    Root root = new Root();
    roots.put(folder, root);
    List<Path> recent = new ArrayList<>();
    for (Path file : listFiles(folder)) {
      root.known.add(file);
      if (loadRecent && isRecent(file)) {
        recent.add(file);
      }
    }
    if (!recent.isEmpty()) {
      LOGGER.info("Loading {} recent files of {}", recent.size(), folder);
      loader.accept(folder, recent.stream().map(Path::toFile).toList());
    }
  }

  synchronized void removeRoot(Path folder) {
    roots.remove(folder);
  }

  private void startPolling() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "Folder watcher"); // NON-NLS
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::pollSafely, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
  }

  private void pollSafely() {
    try {
      poll();
    } catch (RuntimeException e) {
      LOGGER.error("Polling the watched folders", e);
    }
  }

  /** Loads the new files whose copy is finished; returns how many were handed to the loader. */
  synchronized int poll() {
    int total = 0;
    for (Map.Entry<Path, Root> entry : roots.entrySet()) {
      total += poll(entry.getKey(), entry.getValue());
    }
    return total;
  }

  private int poll(Path folder, Root root) {
    if (!Files.isDirectory(folder)) {
      return 0; // Unmounted share: keep the state and try again at the next poll
    }
    List<File> ready = new ArrayList<>();
    for (Path file : listFiles(folder)) {
      if (root.known.contains(file)) {
        continue;
      }
      FileState now = state(file);
      if (now == null) {
        continue;
      }
      FileState before = root.pending.put(file, now);
      if (now.equals(before)) {
        root.pending.remove(file);
        root.known.add(file);
        ready.add(file.toFile());
      }
    }
    if (!ready.isEmpty()) {
      LOGGER.info("Loading {} new files of {}", ready.size(), folder);
      loader.accept(folder, ready);
    }
    return ready.size();
  }

  private boolean isRecent(Path file) {
    FileState s = state(file);
    return s != null && s.modified() >= cutoff().toEpochMilli();
  }

  private Instant cutoff() {
    return Instant.now().minus(window);
  }

  private static FileState state(Path file) {
    try {
      BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
      return new FileState(a.size(), a.lastModifiedTime().toMillis());
    } catch (IOException e) {
      return null;
    }
  }

  /** Files of the folder and of its sub-folders modified within the window. */
  private List<Path> listFiles(Path folder) {
    List<Path> files = new ArrayList<>();
    collect(folder, cutoff().toEpochMilli(), files, true);
    return files;
  }

  private static void collect(Path folder, long cutoff, List<Path> files, boolean isRoot) {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
      for (Path entry : entries) {
        BasicFileAttributes a = Files.readAttributes(entry, BasicFileAttributes.class);
        if (a.isDirectory()) {
          // An old day folder cannot receive new images: skip it rather than list it every time
          if (a.lastModifiedTime().toMillis() >= cutoff) {
            collect(entry, cutoff, files, false);
          }
        } else if (a.isRegularFile() && isCandidate(entry)) {
          files.add(entry.toAbsolutePath().normalize());
        }
      }
    } catch (IOException e) {
      if (isRoot) {
        LOGGER.warn("Cannot list the watched folder {}: {}", folder, e.getMessage());
      }
    }
  }

  /** Skips files that are never DICOM, such as the database or the viewer files of a modality. */
  static boolean isCandidate(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return !(name.endsWith(".mdb")
        || name.endsWith(".ldb")
        || name.endsWith(".exe")
        || name.endsWith(".dll")
        || name.endsWith(".htm")
        || name.endsWith(".html")
        || name.endsWith(".jpg")
        || name.endsWith(".bmp")
        || name.endsWith(".emf")
        || name.endsWith(".db")
        || name.endsWith(".tmp")
        || name.endsWith(".part"));
  }
}
