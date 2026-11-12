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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers where each loaded study came from (a watched folder, an imported folder or a DICOM
 * archive), so that the explorer can label and filter the studies by source.
 */
public final class StudySources {
  private static final Map<String, String> SOURCE_BY_STUDY = new ConcurrentHashMap<>();

  private StudySources() {}

  /** Records the source of a study; the first source seen is kept. */
  public static void register(String studyUid, String source) {
    if (studyUid != null && source != null && !source.isBlank()) {
      SOURCE_BY_STUDY.putIfAbsent(studyUid, source.strip());
    }
  }

  /** The source of the study, empty when it was loaded without one. */
  public static Optional<String> get(String studyUid) {
    return studyUid == null ? Optional.empty() : Optional.ofNullable(SOURCE_BY_STUDY.get(studyUid));
  }
}
