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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.dcm4che3.data.Tag;
import org.weasis.dicom.param.DicomParam;

/** Study date presets of the download window, from "today morning" to "the last 24 hours". */
public enum StudyPeriod {
  ALL,
  TODAY_MORNING,
  TODAY_AFTERNOON,
  TODAY,
  YESTERDAY,
  DAY_BEFORE_YESTERDAY,
  LAST_2_DAYS,
  LAST_7_DAYS,
  LAST_MONTH,
  LAST_3_MONTHS,
  DATE,
  DATE_RANGE,
  LAST_30_MINUTES(Duration.ofMinutes(30)),
  LAST_HOUR(Duration.ofHours(1)),
  LAST_2_HOURS(Duration.ofHours(2)),
  LAST_3_HOURS(Duration.ofHours(3)),
  LAST_6_HOURS(Duration.ofHours(6)),
  LAST_8_HOURS(Duration.ofHours(8)),
  LAST_12_HOURS(Duration.ofHours(12)),
  LAST_24_HOURS(Duration.ofHours(24));

  private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("HHmmss"); // NON-NLS

  private final Duration lastHours;

  StudyPeriod() {
    this(null);
  }

  StudyPeriod(Duration lastHours) {
    this.lastHours = lastHours;
  }

  /** Inclusive start and end of the period, both null for all dates. */
  public record Range(LocalDateTime from, LocalDateTime to) {
    public boolean contains(LocalDateTime dateTime) {
      return from == null
          || dateTime == null
          || (!dateTime.isBefore(from) && !dateTime.isAfter(to));
    }
  }

  /** Whether the archive cannot narrow the query to the hour, so the answers are filtered here. */
  public boolean needsTimeFilter() {
    return lastHours != null;
  }

  /**
   * The period, relative to now; {@code date} and {@code end} are the dates chosen by the user for
   * {@link #DATE} and {@link #DATE_RANGE}.
   */
  public Range range(LocalDateTime now, LocalDate date, LocalDate end) {
    LocalDate today = now.toLocalDate();
    if (lastHours != null) {
      return new Range(now.minus(lastHours), now);
    }
    return switch (this) {
      case ALL -> new Range(null, null);
      case TODAY_MORNING -> new Range(today.atStartOfDay(), today.atTime(11, 59, 59));
      case TODAY_AFTERNOON -> new Range(today.atTime(12, 0), endOf(today));
      case TODAY -> days(today, today);
      case YESTERDAY -> days(today.minusDays(1), today.minusDays(1));
      case DAY_BEFORE_YESTERDAY -> days(today.minusDays(2), today.minusDays(2));
      case LAST_2_DAYS -> days(today.minusDays(1), today);
      case LAST_7_DAYS -> days(today.minusDays(6), today);
      case LAST_MONTH -> days(today.minusMonths(1), today);
      case LAST_3_MONTHS -> days(today.minusMonths(3), today);
      case DATE -> date == null ? new Range(null, null) : days(date, date);
      case DATE_RANGE -> rangeOf(date, end);
      default -> new Range(null, null);
    };
  }

  private static Range rangeOf(LocalDate start, LocalDate end) {
    if (start == null && end == null) {
      return new Range(null, null);
    }
    LocalDate from = start == null ? end : start;
    LocalDate to = end == null ? start : end;
    return from.isAfter(to) ? days(to, from) : days(from, to);
  }

  private static Range days(LocalDate from, LocalDate to) {
    return new Range(from.atStartOfDay(), endOf(to));
  }

  private static LocalDateTime endOf(LocalDate day) {
    return day.atTime(LocalTime.of(23, 59, 59));
  }

  /**
   * Query keys of the period: a date range, with a time range when the period is within one day
   * and does not need a filter here (a time range is matched per day by the archives).
   */
  public static List<DicomParam> queryKeys(Range range, boolean needsTimeFilter) {
    List<DicomParam> keys = new ArrayList<>();
    if (range.from() == null) {
      return keys;
    }
    LocalDate from = range.from().toLocalDate();
    LocalDate to = range.to().toLocalDate();
    keys.add(
        new DicomParam(
            Tag.StudyDate,
            from.equals(to)
                ? from.format(DAY_FORMAT)
                : from.format(DAY_FORMAT) + "-" + to.format(DAY_FORMAT)));
    boolean wholeDays =
        range.from().toLocalTime().equals(LocalTime.MIDNIGHT)
            && range.to().toLocalTime().equals(LocalTime.of(23, 59, 59));
    if (from.equals(to) && !wholeDays && !needsTimeFilter) {
      keys.add(
          new DicomParam(
              Tag.StudyTime,
              range.from().format(TIME_FORMAT) + "-" + range.to().format(TIME_FORMAT)));
    }
    return keys;
  }
}
