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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Description of a ProScan fluorography image in the database of the fluorograph (patients2.mdb,
 * table FluorTab), found next to the daily image folders. A record is matched by the name of the
 * image file, which ProScan stores with the record.
 */
public final class ProScanDatabase {

  public static final String FILE_NAME = "patients2.mdb"; // NON-NLS

  /** An item of a ProScan dictionary: a doctor or a diagnosis. */
  public record Entry(int id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  /** The doctors and diagnoses that a description refers to. */
  public record Catalog(List<Entry> doctors, List<Entry> diagnoses) {}

  /** The record of an image, as read from the database. */
  public record Visit(
      Path database, int recordId, String fileName, String text, int doctorId, int diagnosisId) {}

  /** Runs one request against a database and returns the decoded fields of each output line. */
  interface Runner {
    List<List<String>> run(Map<String, String> args) throws IOException;
  }

  private final Runner runner;
  private final Map<Path, Catalog> catalogs = new ConcurrentHashMap<>();

  public ProScanDatabase() {
    this(new JetRunner());
  }

  ProScanDatabase(Runner runner) {
    this.runner = Objects.requireNonNull(runner);
  }

  /** Whether this computer can open a ProScan database (Windows with its Jet engine). */
  public static boolean isSupported() {
    return JetRunner.executable().isPresent();
  }

  /**
   * Places of the database for an image: the folder holding the daily folders first, where ProScan
   * keeps the shared database, then the folder of the image.
   */
  static List<Path> candidates(Path image) {
    List<Path> places = new ArrayList<>();
    Path folder = image.toAbsolutePath().getParent();
    if (folder != null) {
      if (folder.getParent() != null) {
        places.add(folder.getParent().resolve(FILE_NAME));
      }
      places.add(folder.resolve(FILE_NAME));
    }
    return places;
  }

  /** Whether a database lies where ProScan keeps it for this image. */
  public static boolean hasDatabase(Path image) {
    return candidates(image).stream().anyMatch(Files::isRegularFile);
  }

  /** The record of the image, from the first database holding it. */
  public Optional<Visit> read(Path image) throws IOException {
    String fileName = image.getFileName().toString();
    for (Path db : candidates(image)) {
      if (!Files.isRegularFile(db)) {
        continue;
      }
      for (List<String> line : runner.run(args("read", db, fileName))) { // NON-NLS
        if (line.size() >= 5 && "R".equals(line.getFirst())) {
          return Optional.of(
              new Visit(
                  db,
                  toInt(line.get(1)),
                  fileName,
                  toShown(line.get(2)),
                  toInt(line.get(3)),
                  toInt(line.get(4))));
        }
      }
    }
    return Optional.empty();
  }

  /** The doctors and diagnoses of a database, read once per session. */
  public Catalog catalog(Path db) throws IOException {
    Catalog cached = catalogs.get(db);
    if (cached != null) {
      return cached;
    }
    List<Entry> doctors = new ArrayList<>();
    List<Entry> diagnoses = new ArrayList<>();
    for (List<String> line : runner.run(args("catalog", db, null))) { // NON-NLS
      if (line.size() < 3) {
        continue;
      }
      Entry entry = new Entry(toInt(line.get(1)), Objects.toString(line.get(2), "").strip());
      if (entry.name().isEmpty()) {
        continue;
      }
      switch (line.getFirst()) {
        case "D" -> doctors.add(entry);
        case "G" -> diagnoses.add(entry);
        default -> {
          // Not a dictionary line
        }
      }
    }
    Catalog catalog = new Catalog(List.copyOf(doctors), List.copyOf(diagnoses));
    catalogs.put(db, catalog);
    return catalog;
  }

  /** Saves the description, the doctor and the diagnosis of the record. */
  public void write(Visit visit, String text, int doctorId, int diagnosisId) throws IOException {
    Map<String, String> args = args("write", visit.database(), visit.fileName()); // NON-NLS
    args.put("PROSCAN_ID", String.valueOf(visit.recordId())); // NON-NLS
    args.put("PROSCAN_TEXT", toStored(text)); // NON-NLS
    args.put("PROSCAN_DOCTOR", String.valueOf(doctorId)); // NON-NLS
    args.put("PROSCAN_DIAGNOSIS", String.valueOf(diagnosisId)); // NON-NLS
    for (List<String> line : runner.run(args)) {
      if (line.size() >= 2 && "N".equals(line.getFirst()) && toInt(line.get(1)) == 1) {
        return;
      }
    }
    throw new IOException(ProScanMessages.getString("error.gone"));
  }

  private static Map<String, String> args(String mode, Path db, String fileName) {
    Map<String, String> args = new LinkedHashMap<>();
    args.put("PROSCAN_MODE", mode); // NON-NLS
    args.put("PROSCAN_DB", db.toString()); // NON-NLS
    if (fileName != null) {
      args.put("PROSCAN_FILE", fileName); // NON-NLS
    }
    return args;
  }

  /**
   * Text as ProScan stores it: Windows line breaks and a leading space, which ProScan puts before
   * every description and which its reports rely on; an empty description is a single space.
   */
  static String toStored(String text) {
    String value = text == null ? "" : text.strip().replace("\r\n", "\n").replace("\n", "\r\n");
    return " " + value;
  }

  /** Text as shown to the user, without the leading space of ProScan. */
  static String toShown(String stored) {
    return stored == null ? "" : stored.replace("\r\n", "\n").strip();
  }

  private static int toInt(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      // Jet may answer a double for an integer field
      return (int) Double.parseDouble(value.strip().replace(',', '.'));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Decodes the output of the database script: Base64 fields separated by spaces, '-' for a missing
   * value. A line starting with E reports an error.
   */
  static List<List<String>> parse(List<String> lines) throws IOException {
    List<List<String>> rows = new ArrayList<>();
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.strip().split(" ");
      List<String> fields = new ArrayList<>(parts.length);
      try {
        for (String part : parts) {
          fields.add(
              "-".equals(part)
                  ? null
                  : new String(Base64.getDecoder().decode(part), StandardCharsets.UTF_8));
        }
      } catch (IllegalArgumentException e) {
        continue; // Not a line of the script, such as a warning of PowerShell
      }
      if (fields.isEmpty() || fields.getFirst() == null) {
        continue;
      }
      if ("E".equals(fields.getFirst())) {
        throw new IOException(fields.size() > 1 ? fields.get(1) : "?");
      }
      rows.add(fields);
    }
    return rows;
  }
}
