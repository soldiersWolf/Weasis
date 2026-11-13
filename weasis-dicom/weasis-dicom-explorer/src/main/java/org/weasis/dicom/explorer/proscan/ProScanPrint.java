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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.explorer.LocalPersistence;

/**
 * Prints the result of fluorography examinations, one or two forms per sheet: the facility, the
 * patient, the conclusion, the description and the doctor, with room for the signature.
 */
public final class ProScanPrint implements Printable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProScanPrint.class);

  /** Preference key of the facility printed at the top of the forms. */
  public static final String P_FACILITY = "proscan.print.facility"; // NON-NLS

  private static final String P_TWO_PER_PAGE = "proscan.print.two"; // NON-NLS
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy"); // NON-NLS
  private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm"); // NON-NLS
  private static final BasicStroke DASHED =
      new BasicStroke(0.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {4f}, 0f);

  /** One printed form. */
  public record Sheet(
      String patient,
      LocalDate birth,
      LocalDate date,
      LocalTime time,
      String doctor,
      String conclusion,
      String description) {}

  private final List<Sheet> sheets;
  private final int perPage;
  private final String facility;

  ProScanPrint(List<Sheet> sheets, int perPage, String facility) {
    this.sheets = List.copyOf(sheets);
    this.perPage = Math.max(1, perPage);
    this.facility = facility == null ? "" : facility.strip();
  }

  /** Asks how many forms per sheet and which printer, then prints. */
  public static void print(Component parent, List<Sheet> sheets) {
    if (sheets.isEmpty()) {
      return;
    }
    JTextField facility =
        new JTextField(LocalPersistence.getProperties().getProperty(P_FACILITY, ""), 30);
    JCheckBox two =
        new JCheckBox(
            ProScanMessages.getString("print.two"),
            LocalPersistence.getProperties().getBooleanProperty(P_TWO_PER_PAGE, false));
    JPanel options = new JPanel(new MigLayout("ins 0, wrap 1, gap 4", "[grow,fill]")); // NON-NLS
    options.add(new JLabel(ProScanMessages.getString("print.facility")));
    options.add(facility);
    options.add(two, "gaptop 6"); // NON-NLS
    options.add(
        new JLabel(ProScanMessages.format("print.count", sheets.size())),
        "gaptop 6"); // NON-NLS
    if (JOptionPane.showConfirmDialog(
            parent,
            options,
            ProScanMessages.getString("print.title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        != JOptionPane.OK_OPTION) {
      return;
    }
    LocalPersistence.getProperties().setProperty(P_FACILITY, facility.getText().strip());
    LocalPersistence.getProperties().putBooleanProperty(P_TWO_PER_PAGE, two.isSelected());

    PrinterJob job = PrinterJob.getPrinterJob();
    job.setJobName(ProScanMessages.getString("print.title"));
    job.setPrintable(new ProScanPrint(sheets, two.isSelected() ? 2 : 1, facility.getText()));
    if (job.printDialog()) {
      try {
        job.print();
      } catch (PrinterException e) {
        LOGGER.error("Printing ProScan reports", e);
        JOptionPane.showMessageDialog(
            parent,
            ProScanMessages.format("status.error", e.getMessage()),
            ProScanMessages.getString("print.title"),
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  /** Number of sheets of paper. */
  int pageCount() {
    return (sheets.size() + perPage - 1) / perPage;
  }

  @Override
  public int print(Graphics graphics, PageFormat format, int pageIndex) {
    if (pageIndex >= pageCount()) {
      return NO_SUCH_PAGE;
    }
    Graphics2D g = (Graphics2D) graphics;
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.BLACK);
    int x = (int) format.getImageableX();
    int y = (int) format.getImageableY();
    int width = (int) format.getImageableWidth();
    int height = (int) format.getImageableHeight();
    int slot = height / perPage;
    for (int i = 0; i < perPage; i++) {
      int index = pageIndex * perPage + i;
      if (index >= sheets.size()) {
        break;
      }
      if (i > 0) {
        // Cutting line between the two forms
        g.setStroke(DASHED);
        g.drawLine(x, y + slot * i, x + width, y + slot * i);
        g.setStroke(new BasicStroke(1f));
      }
      drawSheet(g, sheets.get(index), x, y + slot * i + (i > 0 ? 18 : 0), width, slot - 24);
    }
    return PAGE_EXISTS;
  }

  private void drawSheet(Graphics2D g, Sheet sheet, int x, int top, int width, int height) {
    Font normal = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    Font bold = normal.deriveFont(Font.BOLD);
    Font title = normal.deriveFont(Font.BOLD, 14f);
    int y = top;
    if (!facility.isEmpty()) {
      y = centered(g, facility, bold, x, y, width);
    }
    y = centered(g, ProScanMessages.getString("print.heading"), title, x, y + 4, width) + 8;

    y = labeled(g, "print.patient", sheet.patient(), x, y, width, normal, bold);
    if (sheet.birth() != null) {
      y = labeled(g, "print.birth", sheet.birth().format(DAY), x, y, width, normal, bold);
    }
    String when =
        (sheet.date() == null ? "" : sheet.date().format(DAY))
            + (sheet.time() == null ? "" : "  " + sheet.time().format(HOUR));
    y = labeled(g, "print.date", when.strip(), x, y, width, normal, bold) + 6;

    y = labeled(g, "print.conclusion", sheet.conclusion(), x, y, width, normal, bold) + 4;
    int footer = top + height - 2 * g.getFontMetrics(normal).getHeight();
    y = labeled(g, "print.description", sheet.description(), x, y, width, normal, bold, footer);

    g.setFont(normal);
    FontMetrics fm = g.getFontMetrics();
    int bottom = Math.max(y + fm.getHeight(), footer) + fm.getAscent();
    String doctor = ProScanMessages.getString("print.doctor") + " " + nonNull(sheet.doctor());
    g.drawString(doctor, x, bottom);
    String sign = ProScanMessages.getString("print.signature") + " ____________________";
    g.drawString(sign, x + width - fm.stringWidth(sign), bottom);
  }

  private static int centered(Graphics2D g, String text, Font font, int x, int y, int width) {
    g.setFont(font);
    FontMetrics fm = g.getFontMetrics();
    for (String line : wrap(text, width, fm::stringWidth)) {
      g.drawString(line, x + (width - fm.stringWidth(line)) / 2, y + fm.getAscent());
      y += fm.getHeight();
    }
    return y;
  }

  private static int labeled(
      Graphics2D g, String key, String value, int x, int y, int width, Font normal, Font bold) {
    return labeled(g, key, value, x, y, width, normal, bold, Integer.MAX_VALUE);
  }

  /** Draws "Label: value", the value wrapped under the label width, up to a bottom limit. */
  private static int labeled(
      Graphics2D g,
      String key,
      String value,
      int x,
      int y,
      int width,
      Font normal,
      Font bold,
      int limit) {
    String label = ProScanMessages.getString(key) + " ";
    g.setFont(bold);
    FontMetrics fmBold = g.getFontMetrics();
    g.drawString(label, x, y + fmBold.getAscent());
    int indent = fmBold.stringWidth(label);
    g.setFont(normal);
    FontMetrics fm = g.getFontMetrics();
    List<String> lines = wrap(nonNull(value), width - indent, fm::stringWidth);
    if (lines.isEmpty()) {
      return y + fm.getHeight();
    }
    for (String line : lines) {
      if (y + fm.getHeight() > limit) {
        break;
      }
      g.drawString(line, x + indent, y + fm.getAscent());
      y += fm.getHeight();
    }
    return y;
  }

  private static String nonNull(String value) {
    return value == null ? "" : value.strip();
  }

  /** Splits a text into lines no wider than the width, keeping its line breaks. */
  static List<String> wrap(String text, int width, ToIntFunction<String> measure) {
    List<String> lines = new ArrayList<>();
    for (String paragraph : text.split("\\R")) {
      StringBuilder line = new StringBuilder();
      for (String word : paragraph.strip().split("\\s+")) {
        if (word.isEmpty()) {
          continue;
        }
        String candidate = line.isEmpty() ? word : line + " " + word;
        if (measure.applyAsInt(candidate) <= width || line.isEmpty()) {
          line.setLength(0);
          line.append(candidate);
        } else {
          lines.add(line.toString());
          line.setLength(0);
          line.append(word);
        }
      }
      if (!line.isEmpty()) {
        lines.add(line.toString());
      }
    }
    return lines;
  }
}
