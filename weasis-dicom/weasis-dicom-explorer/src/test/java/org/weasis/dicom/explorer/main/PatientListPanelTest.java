/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.dicom.explorer.main.PatientListPanel.DayRow;
import org.weasis.dicom.explorer.main.PatientListPanel.Row;
import org.weasis.dicom.explorer.main.PatientListPanel.VisitRow;

class PatientListPanelTest {

  private static final LocalDate NOV_11 = LocalDate.of(2026, 11, 11);
  private static final LocalDate NOV_12 = LocalDate.of(2026, 11, 12);

  private static VisitRow visit(String name, LocalDate date, String time) {
    MediaSeriesGroup patient = mock(MediaSeriesGroup.class);
    when(patient.toString()).thenReturn(name);
    return new VisitRow(
        patient, mock(MediaSeriesGroup.class), date, time == null ? null : LocalTime.parse(time));
  }

  private static String describe(Row row) {
    return switch (row) {
      case DayRow day -> (day.expanded() ? "v " : "> ") + day.date() + " (" + day.visits() + ")";
      case VisitRow visit -> visit.patient().toString();
    };
  }

  @Test
  void groupsTheVisitsByDayMostRecentFirstAndByTimeWithinADay() {
    List<Row> rows =
        PatientListPanel.buildRows(
            List.of(
                visit("Late", NOV_11, "12:55"),
                visit("Undated", null, null),
                visit("Today", NOV_12, "08:00"),
                visit("Early", NOV_11, "08:08")),
            _ -> true);

    assertEquals(
        List.of(
            "v 2026-11-12 (1)",
            "Today",
            "v 2026-11-11 (2)",
            "Early",
            "Late",
            "v null (1)",
            "Undated"),
        rows.stream().map(PatientListPanelTest::describe).toList());
  }

  @Test
  void aClosedDayShowsOnlyItsHeaderWithTheCount() {
    List<Row> rows =
        PatientListPanel.buildRows(
            List.of(
                visit("Today", NOV_12, "08:00"),
                visit("Early", NOV_11, "08:08"),
                visit("Late", NOV_11, "12:55")),
            NOV_12::equals);

    assertEquals(
        List.of("v 2026-11-12 (1)", "Today", "> 2026-11-11 (2)"),
        rows.stream().map(PatientListPanelTest::describe).toList());
  }

  @Test
  void showsNothingWithoutVisits() {
    assertEquals(List.of(), PatientListPanel.buildRows(List.of(), _ -> true));
  }
}
