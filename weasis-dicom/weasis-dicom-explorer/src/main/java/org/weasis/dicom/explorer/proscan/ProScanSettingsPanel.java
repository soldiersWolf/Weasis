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
import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.miginfocom.swing.MigLayout;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.dicom.explorer.FolderWatcher;
import org.weasis.dicom.explorer.LocalPersistence;

/**
 * Preferences of the ProScan database: where it is, a button to browse it, and its automatic zip
 * copies. Every change is saved at once.
 */
public class ProScanSettingsPanel extends JPanel {

  private final JLabel databaseLabel = new JLabel();
  private final JLabel folderLabel = new JLabel();
  private final JLabel status = new JLabel();
  private final JButton backupNow = new JButton(ProScanMessages.getString("backup.now"));

  public ProScanSettingsPanel() {
    setLayout(new MigLayout("fillx, ins 6, gap 6 6", "[][grow,fill][][]", "")); // NON-NLS
    setBorder(BorderFactory.createTitledBorder(ProScanMessages.getString("settings.title")));

    JButton chooseDb = new JButton(ProScanMessages.getString("settings.choose"));
    chooseDb.addActionListener(_ -> chooseDatabase());
    JButton autoDb = new JButton(ProScanMessages.getString("settings.auto"));
    autoDb.setToolTipText(ProScanMessages.getString("settings.auto.tip"));
    autoDb.addActionListener(
        _ -> {
          LocalPersistence.getProperties().setProperty(ProScanDatabase.P_DATABASE, "");
          refreshDatabase();
        });
    add(new JLabel(ProScanMessages.getString("settings.database")));
    add(databaseLabel, "wmin 0"); // NON-NLS
    add(chooseDb);
    add(autoDb, "wrap"); // NON-NLS

    JButton browse = new JButton(ProScanMessages.getString("settings.browse"));
    browse.addActionListener(_ -> ProScanBrowser.open(this));
    add(browse, "skip, span, wrap"); // NON-NLS

    JCheckBox enabled =
        new JCheckBox(ProScanMessages.getString("backup.enabled"), ProScanBackup.isEnabled());
    enabled.addActionListener(
        _ -> {
          LocalPersistence.getProperties()
              .putBooleanProperty(ProScanBackup.P_ENABLED, enabled.isSelected());
          ProScanBackup.start();
        });
    JComboBox<Integer> every = new JComboBox<>(ProScanBackup.INTERVALS.toArray(Integer[]::new));
    every.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list,
              Object value,
              int index,
              boolean isSelected,
              boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setText(ProScanMessages.getString("backup.every." + value));
            return this;
          }
        });
    every.setSelectedItem(ProScanBackup.getHours());
    every.addActionListener(
        _ ->
            LocalPersistence.getProperties()
                .putIntProperty(ProScanBackup.P_HOURS, (Integer) every.getSelectedItem()));
    JSpinner keep = new JSpinner(new SpinnerNumberModel(ProScanBackup.getKeep(), 1, 1000, 1));
    keep.addChangeListener(
        _ ->
            LocalPersistence.getProperties()
                .putIntProperty(ProScanBackup.P_KEEP, (Integer) keep.getValue()));
    JPanel schedule = new JPanel(new MigLayout("ins 0, gap 6")); // NON-NLS
    schedule.add(enabled);
    schedule.add(every);
    schedule.add(new JLabel(ProScanMessages.getString("backup.keep")), "gapleft 12"); // NON-NLS
    schedule.add(keep);
    schedule.add(new JLabel(ProScanMessages.getString("backup.copies")));
    add(schedule, "newline, span, wrap"); // NON-NLS

    JButton chooseFolder = new JButton(ProScanMessages.getString("settings.choose"));
    chooseFolder.addActionListener(_ -> chooseFolder());
    add(new JLabel(ProScanMessages.getString("backup.folder")));
    add(folderLabel, "wmin 0"); // NON-NLS
    add(chooseFolder, "wrap"); // NON-NLS

    JLabel hint = new JLabel(ProScanMessages.getString("backup.hint"));
    hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    hint.setForeground(UIManager.getColor("Label.disabledForeground"));
    add(hint, "skip, span, wmin 0, wrap"); // NON-NLS

    backupNow.addActionListener(_ -> runBackup());
    status.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    add(backupNow, "skip, split 2, span"); // NON-NLS
    add(status, "growx, wmin 0"); // NON-NLS

    ProScanBackup.addChangeListener(() -> GuiExecutor.execute(this::refreshStatus));
    FolderWatcher.addChangeListener(() -> GuiExecutor.execute(this::refreshDatabase));
    refreshDatabase();
    refreshFolder();
    refreshStatus();
  }

  private void chooseDatabase() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new FileNameExtensionFilter("ProScan (*.mdb)", "mdb")); // NON-NLS
    chooser.setDialogTitle(ProScanMessages.getString("settings.database"));
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      LocalPersistence.getProperties()
          .setProperty(ProScanDatabase.P_DATABASE, chooser.getSelectedFile().getPath());
      refreshDatabase();
    }
  }

  private void chooseFolder() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle(ProScanMessages.getString("backup.folder"));
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File folder = chooser.getSelectedFile();
      LocalPersistence.getProperties().setProperty(ProScanBackup.P_FOLDER, folder.getPath());
      refreshFolder();
    }
  }

  private void refreshDatabase() {
    databaseLabel.setText(ProScanMessages.getString("status.loading"));
    // Looking at a network share can be slow
    ProScanReportPanel.EXECUTOR.execute(
        () -> {
          Optional<Path> db = ProScanDatabase.sharedDatabase(FolderWatcher.getWatchedFolders());
          GuiExecutor.execute(
              () -> {
                databaseLabel.setText(
                    db.map(Path::toString)
                        .orElse(ProScanMessages.getString("settings.database.none")));
                databaseLabel.setToolTipText(databaseLabel.getText());
              });
        });
  }

  private void refreshFolder() {
    folderLabel.setText(
        ProScanBackup.getFolder()
            .map(Path::toString)
            .orElse(ProScanMessages.getString("backup.folder.none")));
    folderLabel.setToolTipText(folderLabel.getText());
  }

  private void runBackup() {
    backupNow.setEnabled(false);
    status.setText(ProScanMessages.getString("backup.running"));
    status.setForeground(UIManager.getColor("Label.disabledForeground"));
    Thread.ofVirtual()
        .name("ProScan backup now") // NON-NLS
        .start(
            () -> {
              ProScanBackup.backupNow();
              GuiExecutor.execute(() -> backupNow.setEnabled(true));
            });
  }

  private void refreshStatus() {
    Optional<ProScanBackup.Status> last = ProScanBackup.lastStatus();
    if (last.isEmpty()) {
      status.setText("");
      return;
    }
    ProScanBackup.Status s = last.get();
    String time =
        s.at()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT));
    if (s.error() != null) {
      status.setText(ProScanMessages.format("backup.failed", time, s.error()));
      Color error = UIManager.getColor("Component.error.focusedBorderColor");
      status.setForeground(error != null ? error : Color.RED);
    } else {
      status.setText(
          ProScanMessages.format(
              "backup.done", time, s.file().getFileName(), String.format("%.1f", s.size() / 1e6)));
      status.setForeground(UIManager.getColor("Label.disabledForeground"));
    }
    status.setToolTipText(s.error() != null ? s.error() : String.valueOf(s.file()));
  }
}
