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
 * Remembers where each loaded study came from, a folder or a DICOM archive, so that the explorer
 * can label, filter and list the studies by source.
 */
public final class StudySources {

  /** Whether a study was read from a folder of this computer or retrieved from an archive. */
  public enum Kind {
    LOCAL,
    REMOTE
  }

  private record Source(String label, Kind kind) {}

  private static final Map<String, Source> SOURCE_BY_STUDY = new ConcurrentHashMap<>();

  private StudySources() {}

  /** Records a study read from a folder; the first source seen is kept. */
  public static void register(String studyUid, String source) {
    register(studyUid, source, Kind.LOCAL);
  }

  /** Records a study retrieved from an archive, which wins over a local source. */
  public static void registerRemote(String studyUid, String archive) {
    if (studyUid != null && archive != null && !archive.isBlank()) {
      SOURCE_BY_STUDY.put(studyUid, new Source(archive.strip(), Kind.REMOTE));
    }
  }

  private static void register(String studyUid, String source, Kind kind) {
    if (studyUid != null && source != null && !source.isBlank()) {
      SOURCE_BY_STUDY.putIfAbsent(studyUid, new Source(source.strip(), kind));
    }
  }

  /** The source of the study, empty when it was loaded without one. */
  public static Optional<String> get(String studyUid) {
    return studyUid == null
        ? Optional.empty()
        : Optional.ofNullable(SOURCE_BY_STUDY.get(studyUid)).map(Source::label);
  }

  /** The kind of source of the study, local when unknown. */
  public static Kind kindOf(String studyUid) {
    Source source = studyUid == null ? null : SOURCE_BY_STUDY.get(studyUid);
    return source == null ? Kind.LOCAL : source.kind();
  }
}
