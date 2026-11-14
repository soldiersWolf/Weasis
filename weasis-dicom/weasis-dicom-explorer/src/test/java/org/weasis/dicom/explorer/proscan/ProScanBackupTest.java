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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.proscan.ProScanBackup.Content;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Row;

class ProScanBackupTest {

  private static final LocalDate NOV_11 = LocalDate.of(2026, 11, 11);
  private static final LocalDate NOV_12 = LocalDate.of(2026, 11, 12);

  @TempDir Path dir;

  /** A synthetic ProScan folder: the shared database and three daily folders. */
  private Path proScanFolder() throws IOException {
    Path root = Files.createDirectories(dir.resolve("proscan"));
    Path db = Files.writeString(root.resolve(ProScanDatabase.FILE_NAME), "shared database");
    for (String day : List.of("10-Nov-2026", "11-Nov-2026", "12-Nov-2026")) {
      Path folder = Files.createDirectories(root.resolve(day));
      Files.writeString(folder.resolve("TestovyiTT" + day + ".dcm"), "image " + day);
      Files.writeString(folder.resolve(ProScanDatabase.FILE_NAME), "archive copy");
    }
    Files.createDirectories(root.resolve("Export"));
    return db;
  }

  private static Map<String, String> unzip(Path zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream stream = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = stream.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  @Test
  void readsTheDateOfTheDailyFoldersOfProScan() {
    assertEquals(Optional.of(NOV_11), ProScanBackup.dayOf(Path.of("11-Nov-2026")));
    assertEquals(Optional.of(NOV_11), ProScanBackup.dayOf(Path.of("11-nov-2026")));
    assertEquals(Optional.empty(), ProScanBackup.dayOf(Path.of("Export")));
  }

  @Test
  void laysOutTheCopyLikeTheProScanFolder() throws Exception {
    Path db = proScanFolder();
    Path target = dir.resolve(ProScanBackup.defaultName(NOV_11, NOV_12));
    Row report =
        new Row(1, NOV_12, LocalTime.of(8, 5), "Тестовий Т.", null, "a.dcm", "Лікар", "", "Норма");
    List<Integer> progress = new ArrayList<>();

    Path written =
        ProScanBackup.export(
            db,
            NOV_11,
            NOV_12,
            new Content(true, true, true),
            List.of(report),
            target,
            dir,
            (done, total) -> progress.add(done),
            () -> false);

    assertEquals(dir.resolve("proscan_2026-11-11_2026-11-12.zip"), written);
    Map<String, String> entries = unzip(written);
    assertEquals(
        List.of(
            "11-Nov-2026/TestovyiTT11-Nov-2026.dcm",
            "11-Nov-2026/patients2.mdb",
            "12-Nov-2026/TestovyiTT12-Nov-2026.dcm",
            "12-Nov-2026/patients2.mdb",
            ProScanDatabase.FILE_NAME,
            ProScanBackup.REPORTS_NAME),
        List.copyOf(entries.keySet()));
    assertEquals("shared database", entries.get(ProScanDatabase.FILE_NAME));
    assertEquals("archive copy", entries.get("12-Nov-2026/patients2.mdb"));
    assertTrue(entries.get(ProScanBackup.REPORTS_NAME).contains("\"Тестовий Т.\""));
    assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), progress);
  }

  @Test
  void savesOnlyWhatWasTicked() throws Exception {
    Path db = proScanFolder();

    Path written =
        ProScanBackup.export(
            db,
            NOV_12,
            NOV_12,
            new Content(false, true, false),
            List.of(),
            dir.resolve("db.zip"),
            dir,
            (done, total) -> {},
            () -> false);

    assertEquals(
        List.of("12-Nov-2026/patients2.mdb", ProScanDatabase.FILE_NAME),
        List.copyOf(unzip(written).keySet()));
  }

  @Test
  void aCancelledCopyLeavesNoFile() throws Exception {
    Path db = proScanFolder();
    Path target = dir.resolve("cancelled.zip");

    assertThrows(
        InterruptedIOException.class,
        () ->
            ProScanBackup.export(
                db,
                NOV_11,
                NOV_12,
                new Content(true, true, false),
                List.of(),
                target,
                dir,
                (done, total) -> {},
                () -> true));
    assertFalse(Files.exists(target));
    assertFalse(Files.exists(dir.resolve("cancelled.zip.part")));
  }

  @Test
  void writesTheReportsForExcel() {
    Row row =
        new Row(
            1,
            NOV_12,
            LocalTime.of(8, 5),
            "Тестовий Т.",
            LocalDate.of(1980, 1, 1),
            "a.dcm",
            "Лікар",
            "Норма",
            "Рядок \"1\"\nРядок 2");

    String csv = ProScanBackup.toCsv(List.of(row));

    assertTrue(csv.startsWith("﻿"));
    assertTrue(
        csv.contains(
            "\"12.11.2026\";\"08:05\";\"Тестовий Т.\";\"01.01.1980\";\"Лікар\";\"Норма\";"
                + "\"Рядок \"\"1\"\"\nРядок 2\";\"a.dcm\"\r\n"));
  }
}
