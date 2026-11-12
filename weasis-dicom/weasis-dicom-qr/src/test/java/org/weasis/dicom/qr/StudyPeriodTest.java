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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.dcm4che3.data.Tag;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.param.DicomParam;

class StudyPeriodTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 1, 30);

  private static List<String> keys(StudyPeriod period, LocalDate date, LocalDate end) {
    StudyPeriod.Range range = period.range(NOW, date, end);
    return StudyPeriod.queryKeys(range, period.needsTimeFilter()).stream()
        .map(p -> (p.getTag() == Tag.StudyDate ? "date=" : "time=") + p.getValues()[0])
        .toList();
  }

  @Test
  void allDatesSendsNoDateKey() {
    assertEquals(List.of(), keys(StudyPeriod.ALL, null, null));
  }

  @Test
  void dayPresetsSendADateOrADateRange() {
    assertEquals(List.of("date=20261112"), keys(StudyPeriod.TODAY, null, null));
    assertEquals(List.of("date=20261111"), keys(StudyPeriod.YESTERDAY, null, null));
    assertEquals(List.of("date=20261110"), keys(StudyPeriod.DAY_BEFORE_YESTERDAY, null, null));
    assertEquals(List.of("date=20261111-20261112"), keys(StudyPeriod.LAST_2_DAYS, null, null));
    assertEquals(List.of("date=20261106-20261112"), keys(StudyPeriod.LAST_7_DAYS, null, null));
  }

  @Test
  void morningAndAfternoonAlsoSendATimeRange() {
    assertEquals(
        List.of("date=20261112", "time=000000-115959"),
        keys(StudyPeriod.TODAY_MORNING, null, null));
    assertEquals(
        List.of("date=20261112", "time=120000-235959"),
        keys(StudyPeriod.TODAY_AFTERNOON, null, null));
  }

  @Test
  void theLastHoursAcrossMidnightQueryBothDaysAndAreFilteredHere() {
    StudyPeriod period = StudyPeriod.LAST_3_HOURS;
    assertTrue(period.needsTimeFilter());
    assertEquals(List.of("date=20261111-20261112"), keys(period, null, null));

    StudyPeriod.Range range = period.range(NOW, null, null);
    assertTrue(range.contains(LocalDateTime.of(2026, 11, 11, 23, 0)));
    assertFalse(range.contains(LocalDateTime.of(2026, 11, 11, 21, 0)));
  }

  @Test
  void aDateRangeIsOrderedWhateverTheOrderOfThePicks() {
    LocalDate a = LocalDate.of(2026, 11, 1);
    LocalDate b = LocalDate.of(2026, 11, 5);
    assertEquals(List.of("date=20261101-20261105"), keys(StudyPeriod.DATE_RANGE, b, a));
    assertEquals(List.of("date=20261101"), keys(StudyPeriod.DATE, a, null));
    assertEquals(List.of(), keys(StudyPeriod.DATE, null, null));
  }

  @Test
  void keysAreDicomParams() {
    DicomParam date =
        StudyPeriod.queryKeys(StudyPeriod.TODAY.range(NOW, null, null), false).getFirst();
    assertArrayEquals(new String[] {"20261112"}, date.getValues());
  }
}
