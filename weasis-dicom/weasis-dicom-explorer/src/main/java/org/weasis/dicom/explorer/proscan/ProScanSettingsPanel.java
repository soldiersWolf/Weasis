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

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.miginfocom.swing.MigLayout;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.dicom.explorer.FolderWatcher;
import org.weasis.dicom.explorer.LocalPersistence;

/**
 * Preferences of the ProScan database: where it is, and the buttons to browse it and to save a
 * copy of a period. Every change is saved at once.
 */
public class ProScanSettingsPanel extends JPanel {

  private final JLabel databaseLabel = new JLabel();

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
    JButton export = new JButton(ProScanMessages.getString("export.open"));
    export.addActionListener(
        _ -> ProScanExportDialog.open(this, LocalDate.now().minusDays(6), LocalDate.now()));
    add(browse, "skip, span, split 2"); // NON-NLS
    add(export, "wrap"); // NON-NLS

    FolderWatcher.addChangeListener(() -> GuiExecutor.execute(this::refreshDatabase));
    refreshDatabase();
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
}
