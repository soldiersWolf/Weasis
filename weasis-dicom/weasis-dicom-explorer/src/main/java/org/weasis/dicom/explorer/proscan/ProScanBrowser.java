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

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.dicom.explorer.FolderWatcher;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Row;

/**
 * Read-only view of the ProScan database: the images of a period with their patient, doctor,
 * conclusion and description. A double click opens the image, whose report is then edited under the
 * patient list.
 */
public final class ProScanBrowser extends JDialog {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProScanBrowser.class);

  /** Periods offered, in days back from today; 0 is today only. */
  private enum Period {
    TODAY(0),
    YESTERDAY(1),
    WEEK(6),
    MONTH(30),
    YEAR(365);

    final int days;

    Period(int days) {
      this.days = days;
    }

    @Override
    public String toString() {
      return ProScanMessages.getString("browse.period." + name().toLowerCase(Locale.ROOT));
    }
  }

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);

  private static volatile Consumer<Path> imageOpener = _ -> {};
  private static ProScanBrowser instance;

  private final Path database;
  private final JComboBox<Period> period = new JComboBox<>(Period.values());
  private final JTextField name = new JTextField(14);
  private final JButton find = new JButton(ProScanMessages.getString("browse.find"));
  private final JLabel count = new JLabel();
  private final Rows rows = new Rows();
  private final JTable table = new JTable(rows);
  private final JTextArea detail = new JTextArea(4, 40);
  private final JButton openImage = new JButton(ProScanMessages.getString("browse.open"));

  private ProScanBrowser(Component parent, Path database) {
    super(SwingUtilities.getWindowAncestor(parent), ProScanMessages.getString("browse.title"));
    this.database = database;
    setModalityType(ModalityType.MODELESS);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);

    period.setSelectedItem(Period.WEEK);
    period.addActionListener(_ -> search());
    name.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, ProScanMessages.getString("browse.name"));
    name.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    name.addActionListener(_ -> search());
    find.addActionListener(_ -> search());
    count.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS

    JPanel top = new JPanel(new MigLayout("ins 8 8 4 8, fillx", "[][][]push[]")); // NON-NLS
    top.add(period);
    top.add(name, "growx"); // NON-NLS
    top.add(find);
    top.add(count);

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoCreateRowSorter(true);
    table.setFillsViewportHeight(true);
    int[] widths = {80, 50, 220, 80, 120, 200, 320};
    for (int i = 0; i < widths.length; i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }
    table.setDefaultRenderer(
        LocalDate.class,
        new DefaultTableCellRenderer() {
          @Override
          protected void setValue(Object value) {
            setText(value instanceof LocalDate date ? date.format(DAY) : "");
          }
        });
    table.getSelectionModel().addListSelectionListener(_ -> showSelected());
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              openSelected();
            }
          }
        });

    detail.setEditable(false);
    detail.setLineWrap(true);
    detail.setWrapStyleWord(true);
    openImage.addActionListener(_ -> openSelected());
    openImage.setEnabled(false);
    JPanel bottom = new JPanel(new MigLayout("fill, ins 4 8 8 8", "[grow,fill][]", "[grow,fill]"));
    bottom.add(new JScrollPane(detail), "grow"); // NON-NLS
    bottom.add(openImage, "top"); // NON-NLS

    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), bottom);
    split.setResizeWeight(0.8);
    JPanel content = new JPanel(new MigLayout("fill, ins 0", "[grow,fill]", "[][grow,fill]"));
    content.add(top, "wrap"); // NON-NLS
    content.add(split, "grow"); // NON-NLS
    setContentPane(content);
    setSize(new Dimension(1100, 650));
    setLocationRelativeTo(parent);
  }

  /** Called by the patient list to open an image picked in the browser. */
  public static void setImageOpener(Consumer<Path> opener) {
    imageOpener = opener == null ? _ -> {} : opener;
  }

  /** Shows the browser of the shared database, or explains why there is none. */
  public static void open(Component parent) {
    if (instance != null && instance.isDisplayable()) {
      instance.toFront();
      return;
    }
    Optional<Path> db = ProScanDatabase.sharedDatabase(FolderWatcher.getWatchedFolders());
    String problem =
        !ProScanDatabase.isSupported()
            ? ProScanMessages.getString("error.os")
            : db.isEmpty() ? ProScanMessages.getString("backup.nodb") : null;
    if (problem != null) {
      JOptionPane.showMessageDialog(
          parent, problem, ProScanMessages.getString("browse.title"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    instance = new ProScanBrowser(parent, db.get());
    instance.setVisible(true);
    instance.search();
  }

  private void search() {
    Period p = (Period) period.getSelectedItem();
    LocalDate to = LocalDate.now();
    LocalDate from = to.minusDays(p == null ? 6 : p.days);
    if (p == Period.YESTERDAY) {
      to = from;
    }
    LocalDate end = to;
    String lastName = name.getText();
    find.setEnabled(false);
    count.setText(ProScanMessages.getString("status.loading"));
    ProScanReportPanel.EXECUTOR.execute(
        () -> {
          try {
            List<Row> found = ProScanReportPanel.DATABASE.browse(database, from, end, lastName);
            GuiExecutor.execute(
                () -> {
                  rows.set(found);
                  count.setText(ProScanMessages.format("browse.count", found.size()));
                  find.setEnabled(true);
                });
          } catch (IOException e) {
            LOGGER.error("Browsing the ProScan database {}", database, e);
            GuiExecutor.execute(
                () -> {
                  count.setText(ProScanMessages.format("status.error", e.getMessage()));
                  find.setEnabled(true);
                });
          }
        });
  }

  private Optional<Row> selected() {
    int view = table.getSelectedRow();
    return view < 0
        ? Optional.empty()
        : Optional.of(rows.get(table.convertRowIndexToModel(view)));
  }

  private void showSelected() {
    Optional<Row> row = selected();
    detail.setText(row.map(Row::description).orElse(""));
    detail.setCaretPosition(0);
    openImage.setEnabled(row.isPresent());
  }

  private void openSelected() {
    selected()
        .ifPresent(
            row ->
                ProScanReportPanel.EXECUTOR.execute(
                    () -> {
                      Optional<Path> file = ProScanDatabase.imageFile(database.getParent(), row);
                      GuiExecutor.execute(
                          () -> {
                            if (file.isPresent()) {
                              imageOpener.accept(file.get());
                            } else {
                              JOptionPane.showMessageDialog(
                                  this,
                                  ProScanMessages.format("browse.nofile", row.fileName()),
                                  getTitle(),
                                  JOptionPane.WARNING_MESSAGE);
                            }
                          });
                    }));
  }

  private static final class Rows extends AbstractTableModel {
    private static final String[] COLUMNS = {
      "browse.col.date", // NON-NLS
      "browse.col.time", // NON-NLS
      "browse.col.patient", // NON-NLS
      "browse.col.birth", // NON-NLS
      "browse.col.doctor", // NON-NLS
      "browse.col.diagnosis", // NON-NLS
      "browse.col.description" // NON-NLS
    };
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm"); // NON-NLS

    private List<Row> data = List.of();

    void set(List<Row> rows) {
      data = List.copyOf(rows);
      fireTableDataChanged();
    }

    Row get(int index) {
      return data.get(index);
    }

    @Override
    public int getRowCount() {
      return data.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return ProScanMessages.getString(COLUMNS[column]);
    }

    @Override
    public Class<?> getColumnClass(int column) {
      return column == 0 || column == 3 ? LocalDate.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int column) {
      Row row = data.get(rowIndex);
      return switch (column) {
        case 0 -> row.date();
        case 1 -> row.time() == null ? "" : row.time().format(HOUR);
        case 2 -> row.patient();
        case 3 -> row.birth();
        case 4 -> row.doctor();
        case 5 -> row.diagnosis();
        default -> row.description().replace('\n', ' ');
      };
    }
  }
}
