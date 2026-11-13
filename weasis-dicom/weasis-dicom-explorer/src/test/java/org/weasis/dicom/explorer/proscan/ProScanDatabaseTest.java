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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Row;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Visit;

class ProScanDatabaseTest {

  private static final String IMAGE = "TestovyiTT1311080000.dcm"; // NON-NLS

  @TempDir Path root;

  /** Answers each request with the given lines and records the requests. */
  private static final class FakeRunner implements ProScanDatabase.Runner {
    final List<Map<String, String>> requests = new ArrayList<>();
    final List<List<String>> answer;

    FakeRunner(List<List<String>> answer) {
      this.answer = answer;
    }

    @Override
    public List<List<String>> run(Map<String, String> args) {
      requests.add(Map.copyOf(args));
      return answer;
    }
  }

  private Path image() throws IOException {
    Path day = Files.createDirectories(root.resolve("13-Nov-2026")); // NON-NLS
    return Files.createFile(day.resolve(IMAGE));
  }

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void looksForTheSharedDatabaseAboveTheDailyFolderFirst() throws IOException {
    Path image = image();
    assertEquals(
        List.of(root.resolve("patients2.mdb"), image.getParent().resolve("patients2.mdb")),
        ProScanDatabase.candidates(image));
  }

  @Test
  void readsTheRecordOfTheImageFromTheSharedDatabase() throws IOException {
    Path image = image();
    Path db = Files.createFile(root.resolve(ProScanDatabase.FILE_NAME));
    FakeRunner runner =
        new FakeRunner(
            List.of(List.of("R", "104044", " Легені без змін\r\nСерце в нормі", "4", "5")));

    Visit visit = new ProScanDatabase(runner).read(image, "").orElseThrow();

    assertEquals(
        new Visit(db, 104044, IMAGE, "Легені без змін\nСерце в нормі", 4, 5, false), visit);
    assertEquals("read", runner.requests.getFirst().get("PROSCAN_MODE"));
    assertEquals(IMAGE, runner.requests.getFirst().get("PROSCAN_FILE"));
  }

  @Test
  void neverFallsBackOnTheArchiveCopyOfTheDailyFolder() throws IOException {
    Path image = image();
    Path shared = Files.createFile(root.resolve(ProScanDatabase.FILE_NAME));
    Files.createFile(image.getParent().resolve(ProScanDatabase.FILE_NAME));
    FakeRunner runner = new FakeRunner(List.of());

    assertTrue(new ProScanDatabase(runner).read(image, "").isEmpty());
    assertEquals(
        List.of(shared.toString()),
        runner.requests.stream().map(r -> r.get("PROSCAN_DB")).toList());
  }

  @Test
  void readsTheArchiveCopyAloneButNeverWritesIt() throws IOException {
    Path image = image();
    Path copy = Files.createFile(image.getParent().resolve(ProScanDatabase.FILE_NAME));
    FakeRunner runner = new FakeRunner(List.of(List.of("R", "9", " Норма", "4", "5")));
    ProScanDatabase database = new ProScanDatabase(runner);

    Visit visit = database.read(image, "").orElseThrow();

    assertEquals(copy, visit.database());
    assertTrue(visit.archiveCopy());
    assertThrows(IOException.class, () -> database.write(visit, "x", 4, 5));
    assertEquals(1, runner.requests.size());
  }

  @Test
  void findsNothingWithoutADatabase() throws IOException {
    Path image = image();
    FakeRunner runner = new FakeRunner(List.of());

    assertTrue(new ProScanDatabase(runner).read(image, "").isEmpty());
    assertTrue(runner.requests.isEmpty());
    assertFalse(ProScanDatabase.hasDatabase(image, ""));
  }

  @Test
  void storesTheTextTheWayProScanDoes() {
    assertEquals(" ", ProScanDatabase.toStored("  "));
    assertEquals(
        " Сколіоз.\r\nЛегені без змін", ProScanDatabase.toStored("Сколіоз.\nЛегені без змін\n"));
    assertEquals("", ProScanDatabase.toShown(" "));
    assertEquals("", ProScanDatabase.toShown(null));
  }

  @Test
  void writesTheChosenDoctorAndDiagnosis() throws IOException {
    FakeRunner runner = new FakeRunner(List.of(List.of("N", "1")));
    Visit visit = new Visit(root.resolve("patients2.mdb"), 7, IMAGE, "", 4, 5, false);

    new ProScanDatabase(runner).write(visit, "Норма", 3, 254);

    Map<String, String> request = runner.requests.getFirst();
    assertEquals("write", request.get("PROSCAN_MODE"));
    assertEquals("7", request.get("PROSCAN_ID"));
    assertEquals(" Норма", request.get("PROSCAN_TEXT"));
    assertEquals("3", request.get("PROSCAN_DOCTOR"));
    assertEquals("254", request.get("PROSCAN_DIAGNOSIS"));
  }

  @Test
  void failsWhenNoRecordWasUpdated() {
    FakeRunner runner = new FakeRunner(List.of(List.of("N", "0")));
    Visit visit = new Visit(root.resolve("patients2.mdb"), 7, IMAGE, "", 4, 5, false);

    assertThrows(IOException.class, () -> new ProScanDatabase(runner).write(visit, "x", 0, 0));
  }

  @Test
  void readsTheDictionariesOncePerDatabase() throws IOException {
    FakeRunner runner =
        new FakeRunner(
            List.of(
                List.of("D", "4", "Лікар Тестовий"),
                List.of("G", "5", "Без патологічних змін."),
                List.of("G", "60", "   ")));
    ProScanDatabase database = new ProScanDatabase(runner);
    Path db = root.resolve("patients2.mdb");

    ProScanDatabase.Catalog catalog = database.catalog(db);
    database.catalog(db);

    assertEquals(List.of(new ProScanDatabase.Entry(4, "Лікар Тестовий")), catalog.doctors());
    assertEquals(
        List.of(new ProScanDatabase.Entry(5, "Без патологічних змін.")), catalog.diagnoses());
    assertEquals(1, runner.requests.size());
  }

  @Test
  void decodesTheScriptOutputAndSkipsOtherLines() throws IOException {
    List<List<String>> rows =
        ProScanDatabase.parse(
            List.of(
                "WARNING: something from PowerShell",
                "",
                b64("R") + " " + b64("12") + " - " + b64("4") + " " + b64("5")));

    assertEquals(1, rows.size());
    assertEquals(Arrays.asList("R", "12", null, "4", "5"), rows.getFirst());
  }

  @Test
  void reportsTheErrorOfTheScript() {
    IOException error =
        assertThrows(
            IOException.class,
            () -> ProScanDatabase.parse(List.of(b64("E") + " " + b64("Файл зайнятий"))));
    assertEquals("Файл зайнятий", error.getMessage());
  }
  @Test
  void findsTheSharedDatabaseFromTheWatchedFolders() throws IOException {
    Path image = image();
    Path shared = Files.createFile(root.resolve(ProScanDatabase.FILE_NAME));
    Files.createFile(image.getParent().resolve(ProScanDatabase.FILE_NAME));

    assertEquals(Optional.of(shared), ProScanDatabase.locate("", List.of(root)));
    assertEquals(Optional.of(shared), ProScanDatabase.locate("", List.of(image.getParent())));
    assertEquals(
        Optional.of(Path.of("chosen.mdb")), ProScanDatabase.locate("chosen.mdb", List.of(root)));
  }

  @Test
  void ignoresALoneArchiveCopy() throws IOException {
    Path image = image();
    Files.createFile(image.getParent().resolve(ProScanDatabase.FILE_NAME));

    assertEquals(Optional.empty(), ProScanDatabase.locate("", List.of(image.getParent())));
  }

  @Test
  void browsesTheImagesWithTheirPatientAndReport() throws IOException {
    FakeRunner runner =
        new FakeRunner(
            List.of(
                List.of(
                    "B",
                    "12",
                    "2026-11-13 00:00:00",
                    "1899-12-30 08:15:30",
                    "Тестовий",
                    "Тест",
                    "Тестович",
                    "1980-01-01 00:00:00",
                    IMAGE,
                    "Лікар Т.Т.",
                    "Без патологічних змін.",
                    " Легені без змін")));
    LocalDate day = LocalDate.of(2026, 11, 13);

    List<Row> rows = new ProScanDatabase(runner).browse(root.resolve("db.mdb"), day, day, " Тест ");

    assertEquals(
        List.of(
            new Row(
                12,
                day,
                LocalTime.of(8, 15, 30),
                "Тестовий Тест Тестович",
                LocalDate.of(1980, 1, 1),
                IMAGE,
                "Лікар Т.Т.",
                "Без патологічних змін.",
                "Легені без змін")),
        rows);
    Map<String, String> request = runner.requests.getFirst();
    assertEquals("2026-11-14", request.get("PROSCAN_TO"));
    assertEquals("Тест", request.get("PROSCAN_NAME"));
  }

  @Test
  void findsTheImageInTheDailyFolderOfItsDate() throws IOException {
    Path image = image();
    Row row = new Row(1, LocalDate.of(2026, 11, 13), null, "", null, IMAGE, "", "", "");
    Row elsewhere = new Row(1, LocalDate.of(2026, 1, 2), null, "", null, IMAGE, "", "", "");

    assertEquals(Optional.of(image), ProScanDatabase.imageFile(root, row));
    assertEquals(Optional.of(image), ProScanDatabase.imageFile(root, elsewhere));
  }
  @Test
  void readsOnlyTheDatabaseChosenInThePreferences() throws IOException {
    Path image = image();
    Files.createFile(root.resolve(ProScanDatabase.FILE_NAME));
    Path chosen = root.resolve("ProScanPC").resolve(ProScanDatabase.FILE_NAME);
    FakeRunner runner = new FakeRunner(List.of(List.of("R", "3", " Норма", "4", "5")));

    Visit visit = new ProScanDatabase(runner).read(image, chosen.toString()).orElseThrow();

    assertEquals(chosen, visit.database());
    assertFalse(visit.archiveCopy());
    assertEquals(chosen.toString(), runner.requests.getFirst().get("PROSCAN_DB"));
    assertTrue(ProScanDatabase.hasDatabase(root.resolve("x.dcm"), chosen.toString()));
  }

  @Test
  void browsesTheCopyOfTheMostRecentDayWithoutASharedDatabase() throws IOException {
    for (String day : List.of("11-Nov-2026", "13-Nov-2026", "12-Nov-2026", "Export")) {
      Path folder = Files.createDirectories(root.resolve(day));
      Files.createFile(folder.resolve(ProScanDatabase.FILE_NAME));
    }
    Path latest = root.resolve("13-Nov-2026").resolve(ProScanDatabase.FILE_NAME);

    assertEquals(Optional.of(latest), ProScanDatabase.latestCopy(List.of(root)));
    assertEquals(
        Optional.of(latest), ProScanDatabase.latestCopy(List.of(root.resolve("11-Nov-2026"))));
    assertEquals(root, ProScanDatabase.imagesRoot(latest));
    assertEquals(root, ProScanDatabase.imagesRoot(root.resolve(ProScanDatabase.FILE_NAME)));
  }
}
