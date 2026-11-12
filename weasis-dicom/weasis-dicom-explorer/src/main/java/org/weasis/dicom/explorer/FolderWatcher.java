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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;

/**
 * Keeps the last imported folder in sync with the explorer: its recent files are loaded at start
 * up and new files, also in new sub-folders such as one per day, are loaded as they appear.
 *
 * <p>The folder is polled rather than watched with a {@code WatchService}, whose events are not
 * reliable on network shares. Only the sub-folders modified within the time window are listed, so
 * a large archive is not walked on every poll, and a file is loaded once its size and modification
 * time stopped changing, so that a file still being copied is not read.
 */
public final class FolderWatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(FolderWatcher.class);

  /** Preference key: keep the last imported folder watched. */
  public static final String P_ENABLED = "weasis.dicom.folder.watch"; // NON-NLS

  /** Preference key: how many days back the files are loaded and the sub-folders are listed. */
  public static final String P_DAYS = "weasis.dicom.folder.watch.days"; // NON-NLS

  private static final String P_ROOT = "folder.watch.root"; // NON-NLS
  private static final long POLL_SECONDS = 20;

  private static FolderWatcher instance;

  private final Consumer<List<File>> loader;
  private final Duration window;
  private final Map<Path, FileState> pending = new HashMap<>();
  private final Set<Path> known = new HashSet<>();
  private volatile Path root;
  private ScheduledExecutorService scheduler;

  /** Size and modification time seen at the previous poll, to tell when a copy is finished. */
  private record FileState(long size, long modified) {}

  FolderWatcher(Consumer<List<File>> loader, Duration window) {
    this.loader = loader;
    this.window = window;
  }

  public static boolean isEnabled() {
    return GuiUtils.getUICore().getSystemPreferences().getBooleanProperty(P_ENABLED, false);
  }

  /** Starts watching the remembered folder, loading its recent files into the model. */
  public static synchronized void start(DicomModel model) {
    if (instance != null || !isEnabled()) {
      return;
    }
    int days = GuiUtils.getUICore().getSystemPreferences().getIntProperty(P_DAYS, 7);
    instance =
        new FolderWatcher(
            files ->
                DicomModel.LOADING_EXECUTOR.execute(
                    new LoadLocalDicom(
                        files.toArray(File[]::new), false, model, OpeningViewer.NONE)),
            Duration.ofDays(Math.max(1, days)));
    instance.startPolling();
    String saved = LocalPersistence.getProperties().getProperty(P_ROOT);
    if (saved != null && !saved.isBlank()) {
      // Listing a network share can be slow: never on the event dispatch thread
      FolderWatcher watcher = instance;
      watcher.scheduler.execute(() -> watcher.setRoot(Path.of(saved), true));
    }
  }

  /**
   * Makes the folder the watched one after the user imported it. Its current files are being
   * loaded by that import, so only the files added afterwards are loaded by the watcher.
   */
  public static synchronized void watchImported(File folder) {
    if (instance != null && folder != null && folder.isDirectory()) {
      Path path = folder.toPath().toAbsolutePath().normalize();
      LocalPersistence.getProperties().setProperty(P_ROOT, path.toString());
      FolderWatcher watcher = instance;
      watcher.scheduler.execute(() -> watcher.setRoot(path, false));
    }
  }

  /** The watched folder, empty when none. */
  public static Optional<Path> getWatchedFolder() {
    FolderWatcher watcher = instance;
    return watcher == null ? Optional.empty() : Optional.ofNullable(watcher.root);
  }

  synchronized void setRoot(Path folder, boolean loadRecent) {
    if (folder.equals(root)) {
      return;
    }
    root = folder;
    known.clear();
    pending.clear();
    List<Path> recent = new ArrayList<>();
    for (Path file : listFiles(folder)) {
      known.add(file);
      if (loadRecent && isRecent(file)) {
        recent.add(file);
      }
    }
    if (!recent.isEmpty()) {
      LOGGER.info("Loading {} recent files of {}", recent.size(), folder);
      loader.accept(recent.stream().map(Path::toFile).toList());
    }
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
      LOGGER.error("Polling the watched folder", e);
    }
  }

  /** Loads the new files whose copy is finished; returns how many were handed to the loader. */
  synchronized int poll() {
    Path folder = root;
    if (folder == null || !Files.isDirectory(folder)) {
      return 0; // Unmounted share: keep the state and try again at the next poll
    }
    List<File> ready = new ArrayList<>();
    for (Path file : listFiles(folder)) {
      if (known.contains(file)) {
        continue;
      }
      FileState now = state(file);
      if (now == null) {
        continue;
      }
      FileState before = pending.put(file, now);
      if (now.equals(before)) {
        pending.remove(file);
        known.add(file);
        ready.add(file.toFile());
      }
    }
    if (!ready.isEmpty()) {
      LOGGER.info("Loading {} new files of {}", ready.size(), folder);
      loader.accept(ready);
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
    long cutoff = cutoff().toEpochMilli();
    collect(folder, cutoff, files, true);
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
