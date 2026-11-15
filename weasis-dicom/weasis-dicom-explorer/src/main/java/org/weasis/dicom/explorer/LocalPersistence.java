/*
 * Copyright (c) 2023 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.api.util.ThreadUtil;
import org.weasis.core.util.PropertiesUtil;

public class LocalPersistence {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocalPersistence.class);

  public static final String FILE_NAME = "import-export.properties"; // NON-NLS

  // Writes one after the other, so that the last change is the one on disk
  private static final ExecutorService WRITER =
      ThreadUtil.newSingleThreadDaemonExecutor("Explorer settings"); // NON-NLS

  private static volatile Path file;

  /** Writes the settings to disk, so that a change is kept even if the application is killed. */
  public interface Writer {
    void write(Path path) throws IOException;
  }

  private LocalPersistence() {}

  public static WProperties getProperties() {
    return GuiUtils.getUICore().getPluginPersistence(LocalPersistence.class.getPackageName());
  }

  /** The file the settings are read from at start and written to. */
  public static void setFile(Path path) {
    file = path;
  }

  /**
   * Writes the settings now, in the background. Settings the user chooses once, such as the ProScan
   * database, must not depend on the application being closed normally.
   */
  public static void save() {
    Path target = file;
    if (target != null) {
      WRITER.execute(() -> saveNow(target));
    }
  }

  /** Writes the settings at once, when the bundle stops. */
  public static void saveNow() {
    Path target = file;
    if (target != null) {
      saveNow(target);
    }
  }

  private static void saveNow(Path target) {
    try {
      replace(target, p -> PropertiesUtil.storeProperties(p, getProperties(), null));
    } catch (IOException e) {
      LOGGER.error("Cannot save the explorer settings into {}", target, e);
    }
  }

  /** Writes a new version beside the file, then puts it in its place: never a half-written file. */
  static void replace(Path target, Writer writer) throws IOException {
    Path folder = target.toAbsolutePath().getParent();
    Files.createDirectories(folder);
    Path temp = Files.createTempFile(folder, target.getFileName().toString(), ".tmp"); // NON-NLS
    try {
      writer.write(temp);
      if (Files.size(temp) == 0 && Files.exists(target) && Files.size(target) > 0) {
        throw new IOException("Nothing was written into " + temp);
      }
      try {
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
