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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.weasis.dicom.explorer.LocalPersistence;

/**
 * Description of a ProScan fluorography image in the database of the fluorograph (patients2.mdb,
 * table FluorTab). A record is matched by the name of the image file, which ProScan stores with the
 * record.
 *
 * <p>The database ProScan works with lies in the folder holding the daily image folders. The
 * database inside a daily folder is the copy that the archiving of ProScan writes after each image
 * and replaces at the next one: it is read only when there is no shared database, and never
 * written, since a change there would be lost.
 */
public final class ProScanDatabase {

  public static final String FILE_NAME = "patients2.mdb"; // NON-NLS

  /** Preference key of the shared database chosen by the user, empty to find it by itself. */
  public static final String P_DATABASE = "proscan.database"; // NON-NLS

  private static final DateTimeFormatter JET_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); // NON-NLS

  /** An item of a ProScan dictionary: a doctor or a diagnosis. */
  public record Entry(int id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  /** The doctors and diagnoses that a description refers to. */
  public record Catalog(List<Entry> doctors, List<Entry> diagnoses) {}

  /**
   * The record of an image, as read from the database or from an archive copy of it. The stored
   * text is the description exactly as read, to tell whether someone changed it before saving.
   */
  public record Visit(
      Path database,
      int recordId,
      String fileName,
      String text,
      int doctorId,
      int diagnosisId,
      boolean archiveCopy,
      String storedText) {}

  /**
   * The record was changed since it was read, in ProScan or on another computer: nothing was
   * saved. Holds what the record contains now.
   */
  public static final class ConflictException extends IOException {
    private final transient Visit current;

    ConflictException(Visit current) {
      super(ProScanMessages.getString("conflict.title"));
      this.current = current;
    }

    public Visit current() {
      return current;
    }
  }

  /** The database is in use by another program for a moment: worth trying again. */
  public static final class BusyException extends IOException {
    BusyException(String message) {
      super(message);
    }
  }

  /** The database cannot be reached: its computer is off, or the network or the share is down. */
  public static final class UnavailableException extends IOException {
    UnavailableException(String message) {
      super(message);
    }
  }

  /** Outcome of a check of a database: the images it holds and the time it took to answer. */
  public record Check(long images, long millis) {}

  // Pauses before each new attempt on a busy database: about 6 s in all
  private static final long[] BUSY_PAUSES = {500, 1000, 1800, 2700};

  /** A line of the database browser: an image with its patient and its report. */
  public record Row(
      int recordId,
      LocalDate date,
      LocalTime time,
      String patient,
      LocalDate birth,
      String fileName,
      String doctor,
      String diagnosis,
      String description) {}

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

  /** The shared database: the one chosen by the user, else the one found by the watched folders. */
  public static Optional<Path> sharedDatabase(List<Path> watchedFolders) {
    return locate(chosenSetting(), watchedFolders);
  }

  private static String chosenSetting() {
    return LocalPersistence.getProperties().getProperty(P_DATABASE, "");
  }

  /**
   * A database to read: the shared one, else the archive copy of the most recent daily folder,
   * which holds every image up to the last one archived. Good for browsing and copies, never for
   * saving.
   */
  public static Optional<Path> browsableDatabase(List<Path> watchedFolders) {
    return sharedDatabase(watchedFolders).or(() -> latestCopy(watchedFolders));
  }

  static Optional<Path> latestCopy(List<Path> watchedFolders) {
    Path best = null;
    LocalDate bestDay = null;
    for (Path folder : watchedFolders) {
      // A watched daily folder stands for its siblings
      Path root = ProScanBackup.dayOf(folder).isPresent() ? folder.getParent() : folder;
      if (root == null) {
        continue;
      }
      try (Stream<Path> days = Files.list(root)) {
        for (Path day : days.filter(Files::isDirectory).toList()) {
          Optional<LocalDate> date = ProScanBackup.dayOf(day);
          Path copy = day.resolve(FILE_NAME);
          if (date.isPresent()
              && (bestDay == null || date.get().isAfter(bestDay))
              && Files.isRegularFile(copy)) {
            best = copy;
            bestDay = date.get();
          }
        }
      } catch (IOException e) {
        // An unreachable folder has no copy
      }
    }
    return Optional.ofNullable(best);
  }

  /** The folder holding the daily image folders of a database or of its archive copy. */
  public static Path imagesRoot(Path db) {
    Path folder = db.toAbsolutePath().getParent();
    return folder != null && ProScanBackup.dayOf(folder).isPresent() && folder.getParent() != null
        ? folder.getParent()
        : folder;
  }

  static Optional<Path> locate(String chosen, List<Path> watchedFolders) {
    if (chosen != null && !chosen.isBlank()) {
      return Optional.of(Path.of(chosen.strip()));
    }
    for (Path folder : watchedFolders) {
      Path inside = folder.resolve(FILE_NAME);
      // The database of a folder holding the daily folders, not the archive copy of a daily folder
      if (Files.isRegularFile(inside) && hasSubfolder(folder)) {
        return Optional.of(inside);
      }
      Path above = folder.toAbsolutePath().getParent();
      if (above != null && Files.isRegularFile(above.resolve(FILE_NAME))) {
        return Optional.of(above.resolve(FILE_NAME));
      }
    }
    return Optional.empty();
  }

  private static boolean hasSubfolder(Path folder) {
    try (Stream<Path> children = Files.list(folder)) {
      return children.anyMatch(Files::isDirectory);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * The images taken between two days included, most recent first, optionally only the patients
   * whose last name starts with the given text.
   */
  public List<Row> browse(Path db, LocalDate from, LocalDate to, String lastName)
      throws IOException {
    return browse(db, from, to, lastName, 3000);
  }

  /** Like {@link #browse(Path, LocalDate, LocalDate, String)}, with a maximum number of images. */
  public List<Row> browse(Path db, LocalDate from, LocalDate to, String lastName, int limit)
      throws IOException {
    Map<String, String> args = args("browse", db, null); // NON-NLS
    args.put("PROSCAN_LIMIT", String.valueOf(Math.max(1, limit))); // NON-NLS
    args.put("PROSCAN_FROM", from.toString()); // NON-NLS
    args.put("PROSCAN_TO", to.plusDays(1).toString()); // NON-NLS
    if (lastName != null && !lastName.isBlank()) {
      args.put("PROSCAN_NAME", lastName.strip()); // NON-NLS
    }
    List<Row> rows = new ArrayList<>();
    for (List<String> line : run(args)) {
      if (line.size() < 12 || !"B".equals(line.getFirst())) {
        continue;
      }
      LocalDateTime date = toDateTime(line.get(2));
      LocalDateTime time = toDateTime(line.get(3));
      LocalDateTime birth = toDateTime(line.get(7));
      rows.add(
          new Row(
              toInt(line.get(1)),
              date == null ? null : date.toLocalDate(),
              time == null ? null : time.toLocalTime(),
              fullName(line.get(4), line.get(5), line.get(6)),
              birth == null ? null : birth.toLocalDate(),
              Objects.toString(line.get(8), ""),
              clean(line.get(9)),
              clean(line.get(10)),
              toShown(line.get(11))));
    }
    return rows;
  }

  private static String fullName(String... parts) {
    return String.join(
        " ", Stream.of(parts).map(ProScanDatabase::clean).filter(p -> !p.isEmpty()).toList());
  }

  private static String clean(String value) {
    return value == null ? "" : value.strip();
  }

  static LocalDateTime toDateTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(value.strip(), JET_DATE_TIME);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * The image file of a record: in the daily folder named after its date, as ProScan names them
   * (11-Nov-2026), else in any daily folder.
   */
  public static Optional<Path> imageFile(Path root, Row row) {
    if (root == null || row.fileName().isBlank()) {
      return Optional.empty();
    }
    if (row.date() != null) {
      String day =
          row.date().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)); // NON-NLS
      Path file = root.resolve(day).resolve(row.fileName());
      if (Files.isRegularFile(file)) {
        return Optional.of(file);
      }
    }
    try (Stream<Path> days = Files.list(root)) {
      return days.filter(Files::isDirectory)
          .map(d -> d.resolve(row.fileName()))
          .filter(Files::isRegularFile)
          .findFirst();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Whether a database lies where ProScan keeps it for this image. */
  public static boolean hasDatabase(Path image) {
    return hasDatabase(image, chosenSetting());
  }

  static boolean hasDatabase(Path image, String chosen) {
    return (chosen != null && !chosen.isBlank())
        || candidates(image).stream().anyMatch(Files::isRegularFile);
  }

  /**
   * The record of the image: from the database chosen in the preferences, else from the shared
   * database above the daily folders, else from the archive copy, read only.
   */
  public Optional<Visit> read(Path image) throws IOException {
    return read(image, chosenSetting());
  }

  /**
   * Like {@link #read(Path)}: the database chosen by the user, when there is one, is the one
   * ProScan works with, such as the database on the ProScan computer itself.
   */
  Optional<Visit> read(Path image, String chosen) throws IOException {
    String fileName = image.getFileName().toString();
    if (chosen != null && !chosen.isBlank()) {
      return readRecord(Path.of(chosen.strip()), fileName, false);
    }
    List<Path> places = candidates(image);
    for (Path db : places) {
      if (Files.isRegularFile(db)) {
        // Never fall back on the copy when the shared database lacks the image: it would be saved
        // where the next archiving erases it
        return readRecord(db, fileName, places.size() > 1 && db.equals(places.getLast()));
      }
    }
    return Optional.empty();
  }

  private Optional<Visit> readRecord(Path db, String fileName, boolean archiveCopy)
      throws IOException {
    for (List<String> line : run(args("read", db, fileName))) { // NON-NLS
      if (line.size() >= 5 && "R".equals(line.getFirst())) {
        return Optional.of(
            new Visit(
                db,
                toInt(line.get(1)),
                fileName,
                toShown(line.get(2)),
                toInt(line.get(3)),
                toInt(line.get(4)),
                archiveCopy,
                Objects.toString(line.get(2), "")));
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
    for (List<String> line : run(args("catalog", db, null))) { // NON-NLS
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
  public Visit write(Visit visit, String text, int doctorId, int diagnosisId) throws IOException {
    return write(visit, text, doctorId, diagnosisId, false);
  }

  /**
   * Saves the report when the record still holds what was read, or whatever it holds when {@code
   * replace} is set.
   *
   * @return the record as saved, to compare with at the next save
   * @throws ConflictException when the record was changed meanwhile; nothing was saved
   */
  public Visit write(Visit visit, String text, int doctorId, int diagnosisId, boolean replace)
      throws IOException {
    if (visit.archiveCopy()) {
      throw new IOException(ProScanMessages.getString("status.archive"));
    }
    String stored = toStored(text);
    Map<String, String> args = args("write", visit.database(), visit.fileName()); // NON-NLS
    args.put("PROSCAN_ID", String.valueOf(visit.recordId())); // NON-NLS
    args.put("PROSCAN_TEXT", stored); // NON-NLS
    args.put("PROSCAN_DOCTOR", String.valueOf(doctorId)); // NON-NLS
    args.put("PROSCAN_DIAGNOSIS", String.valueOf(diagnosisId)); // NON-NLS
    args.put("PROSCAN_OLD_TEXT", visit.storedText()); // NON-NLS
    args.put("PROSCAN_OLD_DOCTOR", String.valueOf(visit.doctorId())); // NON-NLS
    args.put("PROSCAN_OLD_DIAGNOSIS", String.valueOf(visit.diagnosisId())); // NON-NLS
    args.put("PROSCAN_FORCE", replace ? "1" : "0"); // NON-NLS
    for (List<String> line : run(args)) {
      String tag = line.getFirst();
      if (line.size() >= 4 && "C".equals(tag)) {
        String now = Objects.toString(line.get(1), "");
        throw new ConflictException(
            new Visit(
                visit.database(),
                visit.recordId(),
                visit.fileName(),
                toShown(now),
                toInt(line.get(2)),
                toInt(line.get(3)),
                false,
                now));
      }
      if (line.size() >= 2 && "N".equals(tag) && toInt(line.get(1)) == 1) {
        return new Visit(
            visit.database(),
            visit.recordId(),
            visit.fileName(),
            toShown(stored),
            doctorId,
            diagnosisId,
            false,
            stored);
      }
    }
    throw new IOException(ProScanMessages.getString("error.gone"));
  }

  /**
   * Runs a request, and again a little later only while the database is busy: ProScan and other
   * computers use it at the same time, and Jet refuses a record another program is writing. Any
   * other error, such as an unreachable computer, is reported at once.
   */
  private List<List<String>> run(Map<String, String> args) throws IOException {
    for (int attempt = 0; ; attempt++) {
      try {
        return runner.run(args);
      } catch (BusyException e) {
        if (attempt >= BUSY_PAUSES.length) {
          throw new IOException(ProScanMessages.getString("error.busy"), e);
        }
        try {
          Thread.sleep(BUSY_PAUSES[attempt]);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  /** Opens the database and counts its images, to tell whether it can be used from here. */
  public Check check(Path db) throws IOException {
    long start = System.nanoTime();
    for (List<String> line : run(args("check", db, null))) { // NON-NLS
      if (line.size() >= 2 && "K".equals(line.getFirst())) {
        return new Check(toInt(line.get(1)), (System.nanoTime() - start) / 1_000_000);
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
        String message = fields.size() > 1 ? fields.get(1) : "?";
        throw fields.size() > 2 && "1".equals(fields.get(2))
            ? new BusyException(message)
            : new IOException(message);
      }
      rows.add(fields);
    }
    return rows;
  }
}
