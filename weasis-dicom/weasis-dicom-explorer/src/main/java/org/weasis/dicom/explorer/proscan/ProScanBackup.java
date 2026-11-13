/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.proscan;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.api.util.ThreadUtil;
import org.weasis.dicom.explorer.FolderWatcher;
import org.weasis.dicom.explorer.LocalPersistence;

/**
 * Zip copies of the shared ProScan database, made at a chosen interval while Weasis runs, keeping
 * the most recent ones. The settings belong to this computer, so that a single computer of the
 * hospital makes the copies.
 */
public final class ProScanBackup {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProScanBackup.class);

  public static final String P_ENABLED = "proscan.backup.enabled"; // NON-NLS
  public static final String P_HOURS = "proscan.backup.hours"; // NON-NLS
  public static final String P_FOLDER = "proscan.backup.folder"; // NON-NLS
  public static final String P_KEEP = "proscan.backup.keep"; // NON-NLS
  private static final String P_LAST = "proscan.backup.last"; // NON-NLS

  /** Intervals offered, in hours: every hour, 4 hours, 12 hours, day, week. */
  public static final List<Integer> INTERVALS = List.of(1, 4, 12, 24, 168);

  static final String PREFIX = "proscan_"; // NON-NLS
  private static final DateTimeFormatter NAME_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"); // NON-NLS
  private static final int COPY_ATTEMPTS = 3;

  /** Outcome of the last copy: the zip and its size, or the error. */
  public record Status(Instant at, Path file, long size, String error) {}

  private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
  private static ScheduledExecutorService scheduler;
  private static volatile Status last;

  private ProScanBackup() {}

  /** Starts checking every few minutes whether a copy is due. */
  public static synchronized void start() {
    if (scheduler != null) {
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            ThreadUtil.namedDaemonThreadFactory("ProScan backup", true)); // NON-NLS
    scheduler.scheduleWithFixedDelay(ProScanBackup::backupIfDue, 1, 5, TimeUnit.MINUTES);
  }

  public static void addChangeListener(Runnable listener) {
    LISTENERS.add(listener);
  }

  public static Optional<Status> lastStatus() {
    return Optional.ofNullable(last);
  }

  private static WProperties prefs() {
    return LocalPersistence.getProperties();
  }

  public static boolean isEnabled() {
    return prefs().getBooleanProperty(P_ENABLED, false);
  }

  public static int getHours() {
    return prefs().getIntProperty(P_HOURS, 24);
  }

  public static int getKeep() {
    return prefs().getIntProperty(P_KEEP, 30);
  }

  public static Optional<Path> getFolder() {
    String folder = prefs().getProperty(P_FOLDER, "");
    return folder.isBlank() ? Optional.empty() : Optional.of(Path.of(folder));
  }

  private static void backupIfDue() {
    try {
      Instant previous = Instant.ofEpochMilli(prefs().getLongProperty(P_LAST, 0L));
      if (isEnabled()
          && getFolder().isPresent()
          && isDue(previous, Instant.now(), getHours())) {
        backupNow();
      }
    } catch (RuntimeException e) {
      LOGGER.error("ProScan backup", e);
    }
  }

  static boolean isDue(Instant previous, Instant now, int hours) {
    return !now.isBefore(previous.plus(Duration.ofHours(Math.max(1, hours))));
  }

  /** Makes a copy now, whatever the schedule; blocks, never call it on the event thread. */
  public static synchronized Status backupNow() {
    Status status;
    try {
      Path db =
          ProScanDatabase.sharedDatabase(FolderWatcher.getWatchedFolders())
              .orElseThrow(() -> new IOException(ProScanMessages.getString("backup.nodb")));
      Path folder =
          getFolder()
              .orElseThrow(() -> new IOException(ProScanMessages.getString("backup.nofolder")));
      Path zip = zip(db, folder, LocalDateTime.now());
      prune(folder, getKeep());
      status = new Status(Instant.now(), zip, Files.size(zip), null);
      prefs().putLongProperty(P_LAST, status.at().toEpochMilli());
      LOGGER.info("ProScan database copied to {}", zip);
    } catch (IOException | RuntimeException e) {
      LOGGER.error("ProScan backup failed", e);
      status = new Status(Instant.now(), null, 0, String.valueOf(e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      status = new Status(Instant.now(), null, 0, String.valueOf(e.getMessage()));
    }
    last = status;
    LISTENERS.forEach(Runnable::run);
    return status;
  }

  /**
   * Zips the database into the folder. The database is in use, so it is first copied until a copy
   * starts and ends with the same size and date, i.e. no write happened meanwhile; the zip is
   * written under a temporary name and renamed once complete.
   */
  static Path zip(Path db, Path folder, LocalDateTime now)
      throws IOException, InterruptedException {
    Files.createDirectories(folder);
    Path snapshot = Files.createTempFile(folder, ".proscan-", ".tmp"); // NON-NLS
    try {
      FileTime modified = copyStable(db, snapshot);
      Path target = folder.resolve(PREFIX + now.format(NAME_TIME) + ".zip"); // NON-NLS
      Path partial = folder.resolve(target.getFileName() + ".part"); // NON-NLS
      try (OutputStream out = Files.newOutputStream(partial);
          ZipOutputStream zip = new ZipOutputStream(out)) {
        ZipEntry entry = new ZipEntry(db.getFileName().toString());
        entry.setLastModifiedTime(modified);
        zip.putNextEntry(entry);
        Files.copy(snapshot, zip);
        zip.closeEntry();
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } finally {
      Files.deleteIfExists(snapshot);
    }
  }

  private static FileTime copyStable(Path db, Path snapshot)
      throws IOException, InterruptedException {
    for (int attempt = 1; ; attempt++) {
      long size = Files.size(db);
      FileTime modified = Files.getLastModifiedTime(db);
      Files.copy(db, snapshot, StandardCopyOption.REPLACE_EXISTING);
      if (size == Files.size(db) && modified.equals(Files.getLastModifiedTime(db))) {
        return modified;
      }
      if (attempt == COPY_ATTEMPTS) {
        LOGGER.warn("The ProScan database kept changing during its copy, kept the last one");
        return modified;
      }
      // ProScan wrote during the copy: try again a little later
      Thread.sleep(5_000);
    }
  }

  /** Deletes the oldest copies of the folder beyond the number to keep. */
  static void prune(Path folder, int keep) throws IOException {
    List<Path> copies = new ArrayList<>();
    try (Stream<Path> files = Files.list(folder)) {
      files
          .filter(
              f -> {
                String name = f.getFileName().toString();
                return name.startsWith(PREFIX) && name.endsWith(".zip"); // NON-NLS
              })
          .forEach(copies::add);
    }
    // The names hold the date, so their order is the order of the copies
    copies.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
    for (Path old : copies.subList(Math.min(Math.max(1, keep), copies.size()), copies.size())) {
      Files.deleteIfExists(old);
    }
  }
}
