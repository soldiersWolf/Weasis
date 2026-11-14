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

import java.awt.Color;
import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.weasis.core.api.gui.util.AbstractItemDialogPage;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.dicom.explorer.FolderWatcher;
import org.weasis.dicom.explorer.main.PatientListPanel;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode;

/**
 * First page of the preferences, in plain words: how the patient list looks, the watched folders
 * and the archives whose studies of the day are pulled in the background.
 */
public class SourcesView extends AbstractItemDialogPage {

  private final DefaultListModel<Path> folders = new DefaultListModel<>();
  private final JList<Path> folderList = new JList<>(folders);
  private final JPanel archivesPanel = new JPanel(new MigLayout("ins 0, wrap 2, fillx")); // NON-NLS
  private final JSpinner minutes =
      new JSpinner(new SpinnerNumberModel(ServerWatcher.getInstance().getMinutes(), 1, 120, 1));

  public SourcesView() {
    super(MultiArchiveMessages.getString("sources.title"), -10);
    setComponentPosition(-10);
    setLayout(new MigLayout("fill, ins 10, gapy 10", "[grow,fill]", "")); // NON-NLS

    add(wrappingText(MultiArchiveMessages.getString("sources.hint")), "growx, wmin 0, wrap");
    add(buildListPanel(), "growx, wrap"); // NON-NLS

    add(buildFoldersPanel(), "grow, wrap"); // NON-NLS
    add(buildArchivesPanel(), "grow, wrap"); // NON-NLS

    FolderWatcher.addChangeListener(() -> GuiExecutor.execute(this::refreshFolders));
    ServerWatcher.getInstance().addChangeListener(() -> GuiExecutor.execute(this::refreshArchives));
    refreshFolders();
    refreshArchives();
  }

  private JPanel buildListPanel() {
    // One choice per line: side by side, the long labels widen the page beyond its window
    JPanel panel = new JPanel(new MigLayout("ins 6, wrap 1, gap 2")); // NON-NLS
    panel.setBorder(
        BorderFactory.createTitledBorder(MultiArchiveMessages.getString("basics.list")));
    ButtonGroup group = new ButtonGroup();
    String current = PatientListPanel.getDensity();
    for (String density : new String[] {"COMPACT", "NORMAL", "DETAILED"}) { // NON-NLS
      JRadioButton button =
          new JRadioButton(
              MultiArchiveMessages.getString("basics.density." + density),
              density.equals(current));
      button.addActionListener(_ -> PatientListPanel.applyDensity(density));
      group.add(button);
      panel.add(button);
    }
    return panel;
  }

  private JPanel buildFoldersPanel() {
    JPanel panel = new JPanel(new MigLayout("fill, ins 6", "[grow,fill][]", "[][grow,fill]"));
    panel.setBorder(
        BorderFactory.createTitledBorder(MultiArchiveMessages.getString("sources.folders")));
    panel.add(
        wrappingText(MultiArchiveMessages.getString("sources.folders.hint")),
        "span, growx, wmin 0, wrap"); // NON-NLS
    JButton addFolder = new JButton(MultiArchiveMessages.getString("sources.add"));
    JButton removeFolder = new JButton(MultiArchiveMessages.getString("sources.remove"));
    addFolder.addActionListener(_ -> chooseFolder());
    removeFolder.addActionListener(
        _ -> {
          Path selected = folderList.getSelectedValue();
          if (selected != null) {
            FolderWatcher.removeFolder(selected);
          }
        });
    JPanel buttons = new JPanel(new MigLayout("ins 0, wrap 1", "[fill]")); // NON-NLS
    buttons.add(addFolder);
    buttons.add(removeFolder);
    panel.add(new JScrollPane(folderList), "grow, height 70lp:110lp:"); // NON-NLS
    panel.add(buttons, "top"); // NON-NLS
    return panel;
  }

  private JPanel buildArchivesPanel() {
    JPanel panel = new JPanel(new MigLayout("fill, ins 6", "[grow,fill]", "[][grow,fill][]"));
    panel.setBorder(
        BorderFactory.createTitledBorder(MultiArchiveMessages.getString("sources.servers")));
    panel.add(
        wrappingText(MultiArchiveMessages.getString("sources.servers.hint")),
        "growx, wmin 0, wrap"); // NON-NLS
    panel.add(new JScrollPane(archivesPanel), "grow, height 80lp:140lp:, wrap"); // NON-NLS

    JButton checkNow = new JButton(MultiArchiveMessages.getString("sources.check"));
    checkNow.addActionListener(_ -> checkAll());
    minutes.addChangeListener(
        _ -> ServerWatcher.getInstance().setMinutes((Integer) minutes.getValue()));
    JPanel footer = new JPanel(new MigLayout("ins 0, fillx", "[][][]push[]")); // NON-NLS
    footer.add(new JLabel(MultiArchiveMessages.getString("sources.every")));
    footer.add(minutes);
    footer.add(new JLabel(MultiArchiveMessages.getString("sources.minutes")));
    footer.add(checkNow);
    panel.add(footer, "growx"); // NON-NLS
    return panel;
  }

  private void chooseFolder() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle(MultiArchiveMessages.getString("sources.add"));
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File folder = chooser.getSelectedFile();
      FolderWatcher.addFolder(folder);
    }
  }

  private void refreshFolders() {
    folders.clear();
    FolderWatcher.getWatchedFolders().forEach(folders::addElement);
  }

  private void refreshArchives() {
    archivesPanel.removeAll();
    var synced = ServerWatcher.getInstance().getSyncedArchives();
    var archives = ServerWatcher.configuredArchives();
    if (archives.isEmpty()) {
      archivesPanel.add(new JLabel(MultiArchiveMessages.getString("sources.noservers")), "span");
    }
    for (AbstractDicomNode archive : archives) {
      JCheckBox box =
          new JCheckBox(archive.getDescription(), synced.contains(archive.getDescription()));
      box.addActionListener(_ -> ServerWatcher.getInstance().setSynced(archive, box.isSelected()));
      archivesPanel.add(box);
      archivesPanel.add(statusLabel(archive), "growx"); // NON-NLS
    }
    archivesPanel.revalidate();
    archivesPanel.repaint();
  }

  private static JLabel statusLabel(AbstractDicomNode archive) {
    ServerWatcher.Status status = ServerWatcher.getInstance().getStatus(archive);
    JLabel label = new JLabel();
    label.putClientProperty("FlatLaf.styleClass", "small"); // NON-NLS
    if (status == null) {
      label.setText(MultiArchiveMessages.getString("status.never"));
      label.setForeground(UIManager.getColor("Label.disabledForeground"));
      return label;
    }
    String time =
        status
            .checkedAt()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm")); // NON-NLS
    if (status.error() != null) {
      label.setText(MultiArchiveMessages.format("status.error", time, status.error()));
      Color error = UIManager.getColor("Component.error.focusedBorderColor");
      label.setForeground(error != null ? error : Color.RED);
      label.setToolTipText(status.error());
    } else {
      label.setText(
          MultiArchiveMessages.format("status.ok", time, status.found(), status.retrieved()));
    }
    return label;
  }

  private static void checkAll() {
    FolderWatcher.pollNow();
    ServerWatcher.getInstance().checkNow();
  }

  /** A text wrapping to the width of the page, like a label that never widens it. */
  private static JTextArea wrappingText(String text) {
    JTextArea area = new JTextArea(text);
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setBorder(BorderFactory.createEmptyBorder());
    area.setFont(UIManager.getFont("Label.font"));
    area.setForeground(UIManager.getColor("Label.disabledForeground"));
    return area;
  }


  @Override
  public void closeAdditionalWindow() {
    // Every change is applied and saved at once
  }

  @Override
  public void resetToDefaultValues() {
    // Nothing to reset: the sources are what the user chose
  }
}
