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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProScanBackupTest {

  @TempDir Path dir;

  @Test
  void zipsTheDatabaseUnderTheDateOfTheCopy() throws Exception {
    byte[] content = "Standard Jet DB synthetic".getBytes(StandardCharsets.UTF_8);
    Path db = Files.write(dir.resolve(ProScanDatabase.FILE_NAME), content);
    Path folder = dir.resolve("copies");

    Path zip = ProScanBackup.zip(db, folder, LocalDateTime.of(2026, 11, 13, 8, 41));

    assertEquals(folder.resolve("proscan_2026-11-13_08-41.zip"), zip);
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream entries = new ZipInputStream(in)) {
      ZipEntry entry = entries.getNextEntry();
      assertEquals(ProScanDatabase.FILE_NAME, entry.getName());
      assertArrayEquals(content, entries.readAllBytes());
    }
    try (Stream<Path> files = Files.list(folder)) {
      assertEquals(List.of(zip), files.toList());
    }
  }

  @Test
  void keepsOnlyTheMostRecentCopies() throws IOException {
    for (String name :
        List.of(
            "proscan_2026-11-11_08-00.zip",
            "proscan_2026-11-12_08-00.zip",
            "proscan_2026-11-13_08-00.zip",
            "other.zip")) {
      Files.createFile(dir.resolve(name));
    }

    ProScanBackup.prune(dir, 2);

    assertFalse(Files.exists(dir.resolve("proscan_2026-11-11_08-00.zip")));
    assertTrue(Files.exists(dir.resolve("proscan_2026-11-12_08-00.zip")));
    assertTrue(Files.exists(dir.resolve("proscan_2026-11-13_08-00.zip")));
    assertTrue(Files.exists(dir.resolve("other.zip")));
  }

  @Test
  void aCopyIsDueOnceTheIntervalHasPassed() {
    Instant last = Instant.parse("2026-11-13T06:00:00Z");

    assertFalse(ProScanBackup.isDue(last, last.plus(Duration.ofMinutes(59)), 1));
    assertTrue(ProScanBackup.isDue(last, last.plus(Duration.ofHours(1)), 1));
    assertTrue(ProScanBackup.isDue(Instant.EPOCH, last, 24));
  }
}
