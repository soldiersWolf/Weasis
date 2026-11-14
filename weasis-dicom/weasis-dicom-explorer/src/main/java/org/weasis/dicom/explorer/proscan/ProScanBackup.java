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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Row;

/**
 * Zip copy of a period chosen by the user: the daily image folders of the period, the database
 * that ProScan and its viewer need to open them, and a table of the reports readable in Excel.
 */
public final class ProScanBackup {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProScanBackup.class);

  /** What the copy holds. */
  public record Content(boolean images, boolean database, boolean reports) {}

  /** Progress of a copy: files written out of the total. */
  public interface Progress {
    void update(int done, int total);
  }

  static final String REPORTS_NAME = "reports.csv"; // NON-NLS
  private static final int COPY_ATTEMPTS = 3;
  // ProScan names the daily folders 11-Nov-2026; other spellings are accepted as well
  private static final List<DateTimeFormatter> DAY_NAMES =
      Stream.of("dd-MMM-yyyy", "d-MMM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd") // NON-NLS
          .map(
              p ->
                  new DateTimeFormatterBuilder()
                      .parseCaseInsensitive()
                      .appendPattern(p)
                      .toFormatter(Locale.ENGLISH))
          .toList();

  private ProScanBackup() {}

  /** Default name of the copy of a period. */
  public static String defaultName(LocalDate from, LocalDate to) {
    return from.equals(to)
        ? "proscan_" + from + ".zip" // NON-NLS
        : "proscan_" + from + "_" + to + ".zip"; // NON-NLS
  }

  /** Date of a daily folder of ProScan, empty for any other folder. */
  public static Optional<LocalDate> dayOf(Path folder) {
    String name = folder.getFileName() == null ? "" : folder.getFileName().toString();
    for (DateTimeFormatter format : DAY_NAMES) {
      try {
        return Optional.of(LocalDate.parse(name, format));
      } catch (DateTimeParseException e) {
        // Try the next spelling
      }
    }
    return Optional.empty();
  }

  /** The daily folders of the period, oldest first. */
  static List<Path> dayFolders(Path root, LocalDate from, LocalDate to) throws IOException {
    List<Path> days = new ArrayList<>();
    try (Stream<Path> children = Files.list(root)) {
      children
          .filter(Files::isDirectory)
          .filter(
              d ->
                  dayOf(d)
                      .map(date -> !date.isBefore(from) && !date.isAfter(to))
                      .orElse(false))
          .forEach(days::add);
    }
    days.sort(Comparator.comparing(d -> dayOf(d).orElseThrow()));
    return days;
  }

  /**
   * Writes the copy of the period. The zip is written under a temporary name and renamed once
   * complete, so that an interrupted copy never looks finished.
   *
   * @param reports the reports of the period, written as a table when the content asks for it
   * @param workDir where the database is copied before being zipped
   */
  public static Path export(
      Path db,
      LocalDate from,
      LocalDate to,
      Content content,
      List<Row> reports,
      Path target,
      Path workDir,
      Progress progress,
      BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    List<Path> images = new ArrayList<>();
    if (content.images()) {
      for (Path day : dayFolders(ProScanDatabase.imagesRoot(db), from, to)) {
        try (Stream<Path> files = Files.list(day)) {
          files.filter(Files::isRegularFile)
              .filter(f -> !f.getFileName().toString().equalsIgnoreCase(ProScanDatabase.FILE_NAME))
              .sorted()
              .forEach(images::add);
        }
      }
    }
    int total = images.size() + (content.database() ? 1 : 0) + (content.reports() ? 1 : 0);
    int done = 0;
    progress.update(done, total);

    Path partial = target.resolveSibling(target.getFileName() + ".part"); // NON-NLS
    try {
      try (OutputStream out = Files.newOutputStream(partial);
          ZipOutputStream zip = new ZipOutputStream(out)) {
        // The images are JPEG 2000 already: compressing them again only costs time
        zip.setLevel(Deflater.BEST_SPEED);
        for (Path image : images) {
          checkCancelled(cancelled);
          ZipEntry entry =
              new ZipEntry(image.getParent().getFileName() + "/" + image.getFileName());
          entry.setLastModifiedTime(Files.getLastModifiedTime(image));
          zip.putNextEntry(entry);
          Files.copy(image, zip);
          zip.closeEntry();
          progress.update(++done, total);
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION);
        if (content.database()) {
          checkCancelled(cancelled);
          addDatabase(zip, db, workDir);
          progress.update(++done, total);
        }
        if (content.reports()) {
          zip.putNextEntry(new ZipEntry(REPORTS_NAME));
          zip.write(toCsv(reports).getBytes(StandardCharsets.UTF_8));
          zip.closeEntry();
          progress.update(++done, total);
        }
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
      LOGGER.info("ProScan copy of {} to {} written to {}", from, to, target);
      return target;
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
    if (cancelled.getAsBoolean()) {
      throw new InterruptedIOException();
    }
  }

  private static void addDatabase(ZipOutputStream zip, Path db, Path workDir)
      throws IOException, InterruptedException {
    Path snapshot = Files.createTempFile(workDir, "proscan-", ".mdb"); // NON-NLS
    try {
      FileTime modified = copyStable(db, snapshot);
      ZipEntry entry = new ZipEntry(db.getFileName().toString());
      entry.setLastModifiedTime(modified);
      zip.putNextEntry(entry);
      Files.copy(snapshot, zip);
      zip.closeEntry();
    } finally {
      Files.deleteIfExists(snapshot);
    }
  }

  /**
   * Copies the database, which ProScan may be writing, until a copy starts and ends with the same
   * size and date.
   */
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
      Thread.sleep(5_000);
    }
  }

  /**
   * The reports as a table for Excel: UTF-8 with a byte order mark, so that Excel reads the
   * Cyrillic letters, and semicolons, the separator of Excel with a Ukrainian or Russian locale.
   */
  static String toCsv(List<Row> rows) {
    DateTimeFormatter day = DateTimeFormatter.ofPattern("dd.MM.yyyy"); // NON-NLS
    DateTimeFormatter hour = DateTimeFormatter.ofPattern("HH:mm"); // NON-NLS
    StringBuilder csv = new StringBuilder("﻿");
    csv.append(
        String.join(
            ";",
            Stream.of(
                    "browse.col.date", // NON-NLS
                    "browse.col.time", // NON-NLS
                    "browse.col.patient", // NON-NLS
                    "browse.col.birth", // NON-NLS
                    "browse.col.doctor", // NON-NLS
                    "browse.col.diagnosis", // NON-NLS
                    "browse.col.description", // NON-NLS
                    "browse.col.file") // NON-NLS
                .map(k -> cell(ProScanMessages.getString(k)))
                .toList()));
    csv.append("\r\n");
    for (Row r : rows) {
      csv.append(
          String.join(
              ";",
              cell(r.date() == null ? "" : r.date().format(day)),
              cell(r.time() == null ? "" : r.time().format(hour)),
              cell(r.patient()),
              cell(r.birth() == null ? "" : r.birth().format(day)),
              cell(r.doctor()),
              cell(r.diagnosis()),
              cell(r.description()),
              cell(r.fileName())));
      csv.append("\r\n");
    }
    return csv.toString();
  }

  private static String cell(String value) {
    return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
  }
}
