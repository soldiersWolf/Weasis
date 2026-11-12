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

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import net.miginfocom.swing.MigLayout;
import org.dcm4che3.data.Tag;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.main.DicomExplorer.ListPosition;

/**
 * Always-visible list of every loaded patient, with a name search. Selecting a row, with the mouse
 * or the arrow keys, selects the patient and opens its first series, so that a whole screening
 * session can be read by walking down the list.
 */
public class PatientListPanel extends JPanel
    implements PatientSelectionManager.PatientSelectionListener {

  private final DicomExplorer explorer;
  private final ListModel<Object> source;
  private final DefaultListModel<MediaSeriesGroup> shown = new DefaultListModel<>();
  private final JList<MediaSeriesGroup> list = new JList<>(shown);
  private final JTextField search = new JTextField();
  private final JLabel count = new JLabel();
  private boolean adjusting;

  public PatientListPanel(DicomExplorer explorer, ListModel<Object> patients) {
    this.explorer = Objects.requireNonNull(explorer);
    this.source = Objects.requireNonNull(patients);
    setLayout(new MigLayout("fill, ins 2 1 2 1", "[grow,fill]", "[][]2[grow,fill]")); // NON-NLS

    search.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, PatientListMessages.getString("search"));
    search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    search.getDocument().addDocumentListener(new FilterOnEdit());
    count.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new PatientRenderer());
    list.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting() && !adjusting) {
            openSelected();
          }
        });

    JScrollPane scroll = new JScrollPane(list);
    scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));
    add(search, "growx"); // NON-NLS
    add(count, "newline, growx"); // NON-NLS
    add(scroll, "newline, grow, height 160lp:360lp:"); // NON-NLS

    source.addListDataListener(new RefreshOnChange());
    refresh();
  }

  private void openSelected() {
    MediaSeriesGroup patient = list.getSelectedValue();
    if (patient == null) {
      return;
    }
    explorer.selectPatient(patient);
    if (explorer.getSeriesGroupFromPatient(patient, ListPosition.FIRST)
        instanceof DicomSeries series) {
      ThumbnailMouseAndKeyAdapter.openSeriesInDefaultPlugin(
          series, explorer.getDataExplorerModel());
    }
  }

  private void refresh() {
    MediaSeriesGroup selected = list.getSelectedValue();
    String query = search.getText().strip().toLowerCase(Locale.ROOT);
    List<MediaSeriesGroup> matches = new ArrayList<>();
    for (int i = 0; i < source.getSize(); i++) {
      if (source.getElementAt(i) instanceof MediaSeriesGroup patient
          && (query.isEmpty() || patient.toString().toLowerCase(Locale.ROOT).contains(query))) {
        matches.add(patient);
      }
    }
    adjusting = true;
    try {
      shown.clear();
      shown.addAll(matches);
      if (selected != null && shown.contains(selected)) {
        list.setSelectedValue(selected, true);
      }
    } finally {
      adjusting = false;
    }
    count.setText(
        PatientListMessages.format("count", shown.getSize(), countPatients())); // NON-NLS
  }

  private int countPatients() {
    int n = 0;
    for (int i = 0; i < source.getSize(); i++) {
      if (source.getElementAt(i) instanceof MediaSeriesGroup) {
        n++;
      }
    }
    return n;
  }

  /** Moves keyboard focus to the list, so that the arrow keys walk through the patients. */
  public void focusList() {
    list.requestFocusInWindow();
  }

  @Override
  public void patientSelected(MediaSeriesGroup patient) {
    if (patient != null && patient != list.getSelectedValue() && shown.contains(patient)) {
      adjusting = true;
      try {
        list.setSelectedValue(patient, true);
      } finally {
        adjusting = false;
      }
    }
  }

  @Override
  public void patientCleared() {
    adjusting = true;
    try {
      list.clearSelection();
    } finally {
      adjusting = false;
    }
  }

  private String details(MediaSeriesGroup patient) {
    StringJoiner line = new StringJoiner("  ·  "); // NON-NLS
    birthDate(patient).ifPresent(d -> line.add(PatientListMessages.format("born", formatDate(d))));
    String sex = TagD.getTagValue(patient, Tag.PatientSex, String.class);
    if (sex != null && !sex.isBlank()) {
      line.add(PatientListMessages.getString("sex." + sex.strip().toUpperCase(Locale.ROOT)));
    }
    latestStudyDate(patient).ifPresent(d -> line.add(formatDate(d)));
    return line.toString();
  }

  private static Optional<LocalDate> birthDate(MediaSeriesGroup patient) {
    return Optional.ofNullable(TagD.getTagValue(patient, Tag.PatientBirthDate, LocalDate.class));
  }

  private Optional<LocalDate> latestStudyDate(MediaSeriesGroup patient) {
    DicomModel model = explorer.getDataExplorerModel();
    LocalDate latest = null;
    for (MediaSeriesGroup study : model.getChildren(patient)) {
      LocalDate date = TagD.getTagValue(study, Tag.StudyDate, LocalDate.class);
      if (date != null && (latest == null || date.isAfter(latest))) {
        latest = date;
      }
    }
    return Optional.ofNullable(latest);
  }

  private static String formatDate(LocalDate date) {
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT));
  }

  private class PatientRenderer implements ListCellRenderer<MediaSeriesGroup> {
    private final JPanel cell = new JPanel(new BorderLayout(0, 1));
    private final JLabel name = new JLabel();
    private final JLabel info = new JLabel();

    PatientRenderer() {
      cell.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
      name.setFont(name.getFont().deriveFont(Font.BOLD, name.getFont().getSize2D() + 1f));
      info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
      cell.add(name, BorderLayout.NORTH);
      cell.add(info, BorderLayout.SOUTH);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends MediaSeriesGroup> jList,
        MediaSeriesGroup patient,
        int index,
        boolean isSelected,
        boolean cellHasFocus) {
      name.setText(patient.toString());
      info.setText(details(patient));
      cell.setBackground(isSelected ? jList.getSelectionBackground() : jList.getBackground());
      name.setForeground(isSelected ? jList.getSelectionForeground() : jList.getForeground());
      info.setForeground(
          isSelected
              ? jList.getSelectionForeground()
              : UIManager.getColor("Label.disabledForeground"));
      return cell;
    }
  }

  private class FilterOnEdit implements DocumentListener {
    @Override
    public void insertUpdate(DocumentEvent e) {
      refresh();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      refresh();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      refresh();
    }
  }

  private class RefreshOnChange implements ListDataListener {
    @Override
    public void intervalAdded(ListDataEvent e) {
      refresh();
    }

    @Override
    public void intervalRemoved(ListDataEvent e) {
      refresh();
    }

    @Override
    public void contentsChanged(ListDataEvent e) {
      refresh();
    }
  }
}
