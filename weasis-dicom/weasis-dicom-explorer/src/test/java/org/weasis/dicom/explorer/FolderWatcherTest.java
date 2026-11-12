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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderWatcherTest {

  @TempDir Path root;

  private final List<String> loaded = new ArrayList<>();
  private final FolderWatcher watcher =
      new FolderWatcher(
          (_, files) -> files.stream().map(File::getName).forEach(loaded::add),
          Duration.ofDays(7));

  private Path write(String relative, String content) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    return file;
  }

  private static void age(Path path, Duration age) throws IOException {
    Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
  }

  @Test
  void loadsOnlyTheRecentFilesAtStartUp() throws IOException {
    write("12-Nov-2026/a.dcm", "a");
    Path old = write("01-Jan-2026/old.dcm", "old");
    age(old, Duration.ofDays(300));
    age(old.getParent(), Duration.ofDays(300));

    watcher.addRoot(root, true);

    assertEquals(List.of("a.dcm"), loaded);
  }

  @Test
  void loadsANewFileOnceItsCopyIsFinished() throws IOException {
    watcher.addRoot(root, false);
    Path file = write("12-Nov-2026/new.dcm", "part");

    assertEquals(0, watcher.poll(), "First sight: the copy may still be running");
    Files.writeString(file, "part and more");
    assertEquals(0, watcher.poll(), "The size changed: still copying");
    assertEquals(1, watcher.poll());
    assertEquals(List.of("new.dcm"), loaded);
    assertEquals(0, watcher.poll(), "A file is loaded only once");
  }

  @Test
  void doesNotReloadTheFilesOfAnImportedFolder() throws IOException {
    write("11-Nov-2026/imported.dcm", "x");

    watcher.addRoot(root, false);
    watcher.poll();
    watcher.poll();

    assertTrue(loaded.isEmpty());
  }

  @Test
  void picksUpANewDayFolder() throws IOException {
    watcher.addRoot(root, false);
    write("13-Nov-2026/morning.dcm", "m");

    watcher.poll();
    watcher.poll();

    assertEquals(List.of("morning.dcm"), loaded);
  }

  @Test
  void watchesSeveralFoldersAtOnce() throws IOException {
    Path first = Files.createDirectories(root.resolve("ProScan"));
    Path second = Files.createDirectories(root.resolve("Other"));
    watcher.addRoot(first, false);
    watcher.addRoot(second, false);
    Files.writeString(first.resolve("a.dcm"), "a");
    Files.writeString(second.resolve("b.dcm"), "b");

    watcher.poll();
    watcher.poll();

    assertEquals(List.of("a.dcm", "b.dcm"), loaded.stream().sorted().toList());
  }

  @Test
  void stopsLoadingFromARemovedFolder() throws IOException {
    watcher.addRoot(root, false);
    watcher.removeRoot(root);
    write("late.dcm", "x");

    watcher.poll();
    watcher.poll();

    assertTrue(loaded.isEmpty());
  }

  @Test
  void ignoresTheModalityDatabase() {
    assertFalse(FolderWatcher.isCandidate(Path.of("patients2.mdb")));
    assertTrue(FolderWatcher.isCandidate(Path.of("Doe1111083414.dcm")));
    assertTrue(FolderWatcher.isCandidate(Path.of("IM0001")));
  }
}
