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

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.miginfocom.swing.MigLayout;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.dicom.explorer.FolderWatcher;
import org.weasis.dicom.explorer.LocalPersistence;

/**
 * Preferences of the ProScan database: where it is, the buttons to browse it, to save a copy of
 * a period and to build the report forms, and the facility printed on the results. Every change is
 * saved at once.
 */
public class ProScanSettingsPanel extends JPanel {

  private final JLabel databaseLabel = new JLabel();

  public ProScanSettingsPanel() {
    // Each row is short, so that the page fits a narrow preferences window
    setLayout(new MigLayout("fillx, ins 6, gap 6 6, wrap 2", "[][grow,fill]", "")); // NON-NLS
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
    add(chooseDb, "skip, split 2"); // NON-NLS
    add(autoDb);

    JButton browse = new JButton(ProScanMessages.getString("settings.browse"));
    browse.addActionListener(_ -> ProScanBrowser.open(this));
    JButton export = new JButton(ProScanMessages.getString("export.open"));
    export.addActionListener(
        _ -> ProScanExportDialog.open(this, LocalDate.now().minusDays(6), LocalDate.now()));
    JButton forms = new JButton(ProScanMessages.getString("forms.edit"));
    forms.addActionListener(_ -> openForms());
    add(browse, "skip, wmin 0"); // NON-NLS
    add(export, "skip, wmin 0"); // NON-NLS
    add(forms, "skip, wmin 0"); // NON-NLS

    JTextField facility =
        new JTextField(LocalPersistence.getProperties().getProperty(ProScanPrint.P_FACILITY, ""));
    facility
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              private void save() {
                LocalPersistence.getProperties()
                    .setProperty(ProScanPrint.P_FACILITY, facility.getText().strip());
              }

              @Override
              public void insertUpdate(DocumentEvent e) {
                save();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                save();
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                save();
              }
            });
    add(new JLabel(ProScanMessages.getString("print.facility")), "gaptop 6"); // NON-NLS
    add(facility, "wmin 0, gaptop 6"); // NON-NLS

    FolderWatcher.addChangeListener(() -> GuiExecutor.execute(this::refreshDatabase));
    refreshDatabase();
  }

  /** Opens the form constructor with the conclusions of ProScan, read in the background. */
  private void openForms() {
    ProScanReportPanel.EXECUTOR.execute(
        () -> {
          List<String> conclusions = new ArrayList<>();
          if (ProScanDatabase.isSupported()) {
            try {
              Optional<Path> db =
                  ProScanDatabase.browsableDatabase(FolderWatcher.getWatchedFolders());
              if (db.isPresent()) {
                ProScanReportPanel.DATABASE.catalog(db.get()).diagnoses().stream()
                    .map(ProScanDatabase.Entry::name)
                    .distinct()
                    .forEach(conclusions::add);
              }
            } catch (IOException e) {
              // The forms can still be made, with conclusions typed by hand
            }
          }
          GuiExecutor.execute(() -> ProScanFormEditor.open(this, conclusions));
        });
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
          List<Path> watched = FolderWatcher.getWatchedFolders();
          Optional<Path> db = ProScanDatabase.sharedDatabase(watched);
          // Without the working database, an archive copy can still be browsed and copied
          String text =
              db.map(Path::toString)
                  .or(
                      () ->
                          ProScanDatabase.latestCopy(watched)
                              .map(c -> ProScanMessages.format("settings.database.copy", c)))
                  .orElse(ProScanMessages.getString("settings.database.none"));
          GuiExecutor.execute(
              () -> {
                databaseLabel.setText(text);
                databaseLabel.setToolTipText(text);
              });
        });
  }
}
