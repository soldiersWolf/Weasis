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
import java.awt.Color;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.ui.tp.raven.datetime.DatePicker;
import org.weasis.dicom.explorer.FolderWatcher;
import org.weasis.dicom.explorer.LocalPersistence;
import org.weasis.dicom.explorer.proscan.ProScanBackup.Content;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Row;

/**
 * Asks for a period, what to keep and where, then writes the zip copy of that period of ProScan in
 * the background.
 */
public final class ProScanExportDialog extends JDialog {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProScanExportDialog.class);

  private static final String P_LAST_FOLDER = "proscan.export.folder"; // NON-NLS
  private static final int MAX_REPORTS = 1_000_000;

  private final Path database;
  private final DatePicker fromPicker = datePicker();
  private final DatePicker toPicker = datePicker();
  private final JCheckBox images = new JCheckBox(ProScanMessages.getString("export.images"), true);
  private final JCheckBox base = new JCheckBox(ProScanMessages.getString("export.database"), true);
  private final JCheckBox reports =
      new JCheckBox(ProScanMessages.getString("export.reports"), ProScanDatabase.isSupported());
  private final JTextField target = new JTextField(32);
  private final JProgressBar progress = new JProgressBar();
  private final JLabel status = new JLabel(" ");
  private final JButton start = new JButton(ProScanMessages.getString("export.start"));
  private final JButton close = new JButton(ProScanMessages.getString("export.close"));
  private volatile boolean cancelled;
  private Thread worker;

  private ProScanExportDialog(Component parent, Path database, LocalDate from, LocalDate to) {
    super(
        SwingUtilities.getWindowAncestor(parent),
        ProScanMessages.getString("export.title"),
        ModalityType.APPLICATION_MODAL);
    this.database = database;
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            closeOrCancel();
          }
        });

    fromPicker.setSelectedDate(from);
    toPicker.setSelectedDate(to);
    fromPicker.addDateSelectionListener(_ -> suggestName());
    toPicker.addDateSelectionListener(_ -> suggestName());
    reports.setEnabled(ProScanDatabase.isSupported());

    JPanel panel = new JPanel(new MigLayout("ins 12, gap 8 8", "[][grow,fill]", "")); // NON-NLS
    panel.add(new JLabel(ProScanMessages.getString("export.period")));
    JPanel dates = new JPanel(new MigLayout("ins 0, gap 6")); // NON-NLS
    dates.add(fromPicker.getEditor(), "width 110lp!"); // NON-NLS
    dates.add(new JLabel("—"));
    dates.add(toPicker.getEditor(), "width 110lp!"); // NON-NLS
    for (int days : new int[] {0, 6, 30}) {
      JButton quick = new JButton(ProScanMessages.getString("export.quick." + days));
      quick.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
      quick.addActionListener(
          _ -> {
            fromPicker.setSelectedDate(LocalDate.now().minusDays(days));
            toPicker.setSelectedDate(LocalDate.now());
            suggestName();
          });
      dates.add(quick, days == 0 ? "gapleft 12" : ""); // NON-NLS
    }
    panel.add(dates, "wrap"); // NON-NLS

    panel.add(new JLabel(ProScanMessages.getString("export.content")), "top"); // NON-NLS
    JPanel content = new JPanel(new MigLayout("ins 0, wrap 1, gap 2")); // NON-NLS
    content.add(images);
    content.add(base);
    content.add(reports);
    panel.add(content, "wrap"); // NON-NLS

    JButton browse = new JButton(ProScanMessages.getString("settings.choose"));
    browse.addActionListener(_ -> chooseTarget());
    panel.add(new JLabel(ProScanMessages.getString("export.target")));
    panel.add(target, "split 2, growx, wmin 0"); // NON-NLS
    panel.add(browse, "wrap"); // NON-NLS

    JLabel hint = new JLabel(ProScanMessages.getString("export.hint"));
    hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    hint.setForeground(UIManager.getColor("Label.disabledForeground"));
    panel.add(hint, "skip, wmin 0, wrap"); // NON-NLS

    progress.setStringPainted(true);
    progress.setVisible(false);
    panel.add(progress, "span, growx, hidemode 3, wrap"); // NON-NLS
    status.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    panel.add(status, "span, growx, wmin 0, wrap"); // NON-NLS

    start.addActionListener(_ -> startExport());
    close.addActionListener(_ -> closeOrCancel());
    panel.add(start, "span, split 2, right"); // NON-NLS
    panel.add(close);
    getRootPane().setDefaultButton(start);

    setContentPane(panel);
    suggestName();
    pack();
    setLocationRelativeTo(parent);
  }

  /** Opens the copy of a period, the given days by default. */
  public static void open(Component parent, LocalDate from, LocalDate to) {
    Optional<Path> db = ProScanDatabase.sharedDatabase(FolderWatcher.getWatchedFolders());
    if (db.isEmpty()) {
      JOptionPane.showMessageDialog(
          parent,
          ProScanMessages.getString("backup.nodb"),
          ProScanMessages.getString("export.title"),
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    new ProScanExportDialog(parent, db.get(), from, to).setVisible(true);
  }

  private static DatePicker datePicker() {
    DatePicker picker = new DatePicker();
    picker.setStartWeekOnMonday(true);
    picker.setCloseAfterSelected(true);
    picker.setEditor(new JFormattedTextField());
    return picker;
  }

  private Path lastFolder() {
    String folder = LocalPersistence.getProperties().getProperty(P_LAST_FOLDER, "");
    return folder.isBlank() ? Path.of(System.getProperty("user.home")) : Path.of(folder);
  }

  private void suggestName() {
    LocalDate from = fromPicker.getSelectedDate();
    LocalDate to = toPicker.getSelectedDate();
    if (from == null || to == null) {
      return;
    }
    Path folder = currentTarget().map(Path::getParent).orElse(lastFolder());
    target.setText(folder.resolve(ProScanBackup.defaultName(from, to)).toString());
  }

  private Optional<Path> currentTarget() {
    String text = target.getText().strip();
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      Path path = Path.of(text);
      return path.getParent() == null ? Optional.empty() : Optional.of(path);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private void chooseTarget() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new FileNameExtensionFilter("ZIP (*.zip)", "zip")); // NON-NLS
    currentTarget().ifPresent(p -> chooser.setSelectedFile(p.toFile()));
    if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
      File file = chooser.getSelectedFile();
      String name = file.getName().toLowerCase(Locale.ROOT);
      target.setText(name.endsWith(".zip") ? file.getPath() : file.getPath() + ".zip"); // NON-NLS
    }
  }

  private void startExport() {
    LocalDate from = fromPicker.getSelectedDate();
    LocalDate to = toPicker.getSelectedDate();
    Optional<Path> file = currentTarget();
    if (from == null || to == null || from.isAfter(to)) {
      showStatus(ProScanMessages.getString("export.baddates"), true);
      return;
    }
    if (file.isEmpty()) {
      showStatus(ProScanMessages.getString("export.notarget"), true);
      return;
    }
    Content content = new Content(images.isSelected(), base.isSelected(), reports.isSelected());
    if (!content.images() && !content.database() && !content.reports()) {
      showStatus(ProScanMessages.getString("export.nothing"), true);
      return;
    }
    if (file.get().toFile().exists()
        && JOptionPane.showConfirmDialog(
                this,
                ProScanMessages.format("export.overwrite", file.get().getFileName()),
                getTitle(),
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) {
      return;
    }
    LocalPersistence.getProperties()
        .setProperty(P_LAST_FOLDER, file.get().getParent().toString());
    setRunning(true);
    showStatus(ProScanMessages.getString("export.running"), false);
    cancelled = false;
    worker =
        Thread.ofVirtual()
            .name("ProScan export") // NON-NLS
            .start(() -> export(from, to, content, file.get()));
  }

  private void export(LocalDate from, LocalDate to, Content content, Path file) {
    try {
      List<Row> rows =
          content.reports()
              ? ProScanReportPanel.DATABASE.browse(database, from, to, null, MAX_REPORTS)
              : List.of();
      Path written =
          ProScanBackup.export(
              database,
              from,
              to,
              content,
              rows,
              file,
              AppProperties.FILE_CACHE_DIR,
              (done, total) ->
                  GuiExecutor.execute(
                      () -> {
                        progress.setMaximum(Math.max(1, total));
                        progress.setValue(done);
                        progress.setString(done + " / " + total);
                      }),
              () -> cancelled);
      GuiExecutor.execute(
          () -> {
            setRunning(false);
            showStatus(
                ProScanMessages.format(
                    "export.done",
                    written.getFileName(),
                    String.format("%.1f", written.toFile().length() / 1e6)),
                false);
          });
    } catch (InterruptedIOException e) {
      GuiExecutor.execute(
          () -> {
            setRunning(false);
            showStatus(ProScanMessages.getString("export.cancelled"), true);
          });
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOGGER.error("ProScan export to {}", file, e);
      GuiExecutor.execute(
          () -> {
            setRunning(false);
            showStatus(ProScanMessages.format("export.failed", e.getMessage()), true);
          });
    }
  }

  private void setRunning(boolean running) {
    start.setEnabled(!running);
    progress.setVisible(running || progress.getValue() > 0);
    close.setText(ProScanMessages.getString(running ? "export.cancel" : "export.close"));
    pack();
  }

  private void closeOrCancel() {
    if (worker != null && worker.isAlive()) {
      cancelled = true;
      return;
    }
    dispose();
  }

  private void showStatus(String message, boolean error) {
    status.setText(message);
    status.setToolTipText(message);
    Color color =
        error
            ? UIManager.getColor("Component.error.focusedBorderColor")
            : UIManager.getColor("Label.disabledForeground");
    status.setForeground(color != null ? color : error ? Color.RED : Color.GRAY);
  }
}
