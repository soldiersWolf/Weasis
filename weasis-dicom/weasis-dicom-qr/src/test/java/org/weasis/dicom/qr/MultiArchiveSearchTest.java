/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.qr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.junit.jupiter.api.Test;
import org.weasis.core.api.media.data.MediaSeriesGroupNode;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode.UsageType;
import org.weasis.dicom.explorer.pref.node.DefaultDicomNode;
import org.weasis.dicom.param.DicomState;

class MultiArchiveSearchTest {

  private static final DefaultDicomNode FIRST =
      new DefaultDicomNode("First", "FIRST", "host1", 104, UsageType.BOTH); // NON-NLS
  private static final DefaultDicomNode SECOND =
      new DefaultDicomNode("Second", "SECOND", "host2", 104, UsageType.BOTH); // NON-NLS
  private static final DefaultDicomNode DOWN =
      new DefaultDicomNode("Down", "DOWN", "host3", 104, UsageType.BOTH); // NON-NLS

  private static Attributes study(String studyUid) {
    Attributes item = new Attributes();
    item.setString(Tag.PatientName, VR.PN, "Doe^Jane"); // NON-NLS
    item.setString(Tag.PatientID, VR.LO, "P1"); // NON-NLS
    item.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
    return item;
  }

  private static DicomState answer(Attributes... items) {
    DicomState state = mock(DicomState.class);
    when(state.getDicomRSP()).thenReturn(List.of(items));
    when(state.getStatus()).thenReturn(Status.Success);
    return state;
  }

  private static MultiArchiveSearch searchAll() {
    MultiArchiveSearch search = new MultiArchiveSearch(Map.of());
    search.run(
        List.of(FIRST, SECOND, DOWN),
        node -> {
          if (node == FIRST) {
            return answer(study("1.1"));
          }
          if (node == SECOND) {
            return answer(study("1.1"), study("1.2"));
          }
          throw new IOException("Connection refused"); // NON-NLS
        },
        _ -> {
          throw new AssertionError("No web archive in this test"); // NON-NLS
        });
    return search;
  }

  @Test
  void mergesTheStudiesOfAllArchivesUnderOnePatient() {
    MultiArchiveSearch search = searchAll();

    var patients = search.getModel().getChildren(MediaSeriesGroupNode.rootNode);
    assertEquals(1, patients.size());
    assertEquals(2, search.getModel().getChildren(patients.iterator().next()).size());
  }

  @Test
  void remembersWhichArchiveReturnedEachStudy() {
    MultiArchiveSearch search = searchAll();

    // A study found on two archives is retrieved from one of them, never twice
    AbstractDicomNode first = search.getArchive("1.1").orElseThrow();
    assertTrue(first == FIRST || first == SECOND);
    assertEquals(SECOND, search.getArchive("1.2").orElseThrow());
    assertFalse(search.getArchive("9.9").isPresent());
  }

  @Test
  void reportsTheArchivesThatDidNotAnswer() {
    MultiArchiveSearch search = searchAll();

    assertEquals(Set.of(DOWN), search.getFailures().keySet());
  }

  @Test
  void splitsTheRetrieveByArchive() {
    MultiArchiveSearch search = searchAll();
    RetrieveSelection selection = new RetrieveSelection();
    selection.addStudy("1.1");
    selection.addSeries("1.2", "1.2.1");

    Map<AbstractDicomNode, RetrieveSelection> split = search.splitByArchive(selection);

    RetrieveSelection fromSecond = split.get(SECOND);
    assertEquals(Set.of("1.2.1"), fromSecond.getSeriesUids("1.2"));
    AbstractDicomNode owner = search.getArchive("1.1").orElseThrow();
    assertTrue(split.get(owner).getStudyUids().contains("1.1"));
    assertTrue(split.get(owner).getSeriesUids("1.1").isEmpty());
  }
}
