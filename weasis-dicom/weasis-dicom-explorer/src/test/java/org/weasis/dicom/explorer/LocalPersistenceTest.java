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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPersistenceTest {
  @TempDir Path dir;

  @Test
  void replacesTheFileAndLeavesNoTemporaryFile() throws IOException {
    Path file = dir.resolve(LocalPersistence.FILE_NAME);
    Files.writeString(file, "proscan.database=old", StandardCharsets.UTF_8);

    LocalPersistence.replace(
        file, p -> Files.writeString(p, "proscan.database=new", StandardCharsets.UTF_8));

    assertEquals("proscan.database=new", Files.readString(file, StandardCharsets.UTF_8));
    assertEquals(1, count(dir));
  }

  @Test
  void createsTheFolderOfANewFile() throws IOException {
    Path file = dir.resolve("data").resolve(LocalPersistence.FILE_NAME);

    LocalPersistence.replace(file, p -> Files.writeString(p, "a=1", StandardCharsets.UTF_8));

    assertEquals("a=1", Files.readString(file, StandardCharsets.UTF_8));
  }

  @Test
  void keepsTheSettingsWhenTheWriteFails() throws IOException {
    Path file = dir.resolve(LocalPersistence.FILE_NAME);
    Files.writeString(file, "proscan.database=kept", StandardCharsets.UTF_8);

    assertThrows(
        IOException.class,
        () ->
            LocalPersistence.replace(
                file,
                p -> {
                  Files.writeString(p, "proscan.data", StandardCharsets.UTF_8);
                  throw new IOException("disk full");
                }));
    assertThrows(IOException.class, () -> LocalPersistence.replace(file, p -> {}));

    assertEquals("proscan.database=kept", Files.readString(file, StandardCharsets.UTF_8));
    assertEquals(1, count(dir));
  }

  private static long count(Path folder) throws IOException {
    try (Stream<Path> files = Files.list(folder)) {
      return files.count();
    }
  }
}
