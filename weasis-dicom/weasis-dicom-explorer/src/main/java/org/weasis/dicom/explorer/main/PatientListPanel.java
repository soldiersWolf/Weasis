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
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Predicate;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import net.miginfocom.swing.MigLayout;
import org.dcm4che3.data.Tag;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.ui.editor.image.ViewerPlugin;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.DicomSorter;
import org.weasis.dicom.explorer.FolderWatcher;

/**
 * Always-visible list of every loaded visit, grouped by day with the most recent day first and a
 * name search. Picking a visit, with the mouse or the arrow keys, opens its images in a single
 * viewer, so that a screening session is read by walking down the list. Visits loaded later, for
 * instance by the folder watcher, appear in place without disturbing the selection.
 */
public class PatientListPanel extends JPanel
    implements PatientSelectionManager.PatientSelectionListener {

  /** One line of the list: a day header or a visit. */
  sealed interface Row permits DayRow, VisitRow {}

  /** Header of a day with its number of visits; a null date gathers the undated ones. */
  record DayRow(LocalDate date, int visits, boolean expanded) implements Row {}

  /** A study of a patient, the unit that is opened. */
  record VisitRow(MediaSeriesGroup patient, MediaSeriesGroup study, LocalDate date, LocalTime time)
      implements Row {}

  private static final Comparator<VisitRow> DAY_ORDER =
      Comparator.comparing(VisitRow::date, Comparator.nullsLast(Comparator.reverseOrder()));
  private static final Comparator<VisitRow> VISIT_ORDER =
      DAY_ORDER
          .thenComparing(VisitRow::time, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(v -> v.patient().toString());

  private final DicomExplorer explorer;
  private final ListModel<Object> source;
  private final DefaultListModel<Row> shown = new DefaultListModel<>();
  private final JList<Row> list = new JList<>(shown);
  private final JTextField search = new JTextField();
  private final JLabel count = new JLabel();
  private final JLabel watched = new JLabel();
  private final Timer refreshLater = new Timer(400, _ -> refresh());
  // Days the user opened or closed; the other days follow the default (only the latest is open)
  private final Map<LocalDate, Boolean> toggledDays = new HashMap<>();
  private boolean adjusting;

  public PatientListPanel(DicomExplorer explorer, ListModel<Object> patients) {
    this.explorer = Objects.requireNonNull(explorer);
    this.source = Objects.requireNonNull(patients);
    setLayout(
        new MigLayout("fill, ins 2 1 2 1", "[grow,fill]", "[][]2[grow,fill]2[]")); // NON-NLS

    search.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, PatientListMessages.getString("search"));
    search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    search.getDocument().addDocumentListener(new FilterOnEdit());
    count.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    watched.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new RowRenderer());
    list.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()
              && !adjusting
              && list.getSelectedValue() instanceof VisitRow visit) {
            open(visit);
          }
        });
    list.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            int index = list.locationToIndex(e.getPoint());
            if (index >= 0
                && list.getCellBounds(index, index).contains(e.getPoint())
                && shown.get(index) instanceof DayRow day) {
              toggle(day, !day.expanded());
            }
          }
        });
    bindKey(KeyEvent.VK_RIGHT, "expandDay", true); // NON-NLS
    bindKey(KeyEvent.VK_ENTER, "toggleDay", null); // NON-NLS
    bindKey(KeyEvent.VK_SPACE, "toggleDayBySpace", null); // NON-NLS
    bindKey(KeyEvent.VK_LEFT, "collapseDay", false); // NON-NLS

    JScrollPane scroll = new JScrollPane(list);
    scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));
    add(search, "growx"); // NON-NLS
    add(count, "newline, growx"); // NON-NLS
    add(scroll, "newline, grow, height 160lp:360lp:"); // NON-NLS
    add(watched, "newline, growx, hidemode 3"); // NON-NLS

    refreshLater.setRepeats(false);
    source.addListDataListener(new RefreshOnChange());
    explorer
        .getDataExplorerModel()
        .addPropertyChangeListener(
            evt -> {
              if (evt instanceof ObservableEvent event
                  && switch (event.getActionCommand()) {
                    case ADD, REMOVE, UPDATE, LOADING_STOP -> true;
                    default -> false;
                  }) {
                GuiExecutor.execute(refreshLater::restart);
              }
            });
    refresh();
  }

  /**
   * Binds a key acting on the selected day header: expand, collapse or, with a null state, toggle.
   * On a visit, the left key collapses the day of the visit.
   */
  private void bindKey(int key, String name, Boolean expand) {
    list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
    list.getActionMap()
        .put(
            name,
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                Row row = list.getSelectedValue();
                if (row instanceof DayRow day) {
                  toggle(day, expand == null ? !day.expanded() : expand);
                } else if (row instanceof VisitRow visit && Boolean.FALSE.equals(expand)) {
                  toggle(new DayRow(visit.date(), 0, true), false);
                }
              }
            });
  }

  private void toggle(DayRow day, boolean expand) {
    toggledDays.put(day.date(), expand);
    refresh();
    for (int i = 0; i < shown.size(); i++) {
      if (shown.get(i) instanceof DayRow d && Objects.equals(d.date(), day.date())) {
        adjusting = true;
        try {
          list.setSelectedIndex(i);
          list.ensureIndexIsVisible(expand ? Math.min(i + d.visits(), shown.size() - 1) : i);
          list.ensureIndexIsVisible(i);
        } finally {
          adjusting = false;
        }
        return;
      }
    }
  }

  private void open(VisitRow visit) {
    explorer.selectPatient(visit.patient());
    DicomModel model = explorer.getDataExplorerModel();
    firstSeries(model, visit.study())
        .ifPresent(
            series -> {
              // One viewer at a time: the picked visit replaces the previous one
              List<ViewerPlugin<?>> open =
                  new ArrayList<>(GuiUtils.getUICore().getViewerPlugins());
              if (!open.isEmpty()) {
                GuiUtils.getUICore().closeSeriesViewer(open);
              }
              ThumbnailMouseAndKeyAdapter.openSeriesInDefaultPlugin(series, model);
            });
  }

  /** First displayable series of the study, in the explorer's series order. */
  static Optional<DicomSeries> firstSeries(DicomModel model, MediaSeriesGroup study) {
    List<MediaSeriesGroup> series = new ArrayList<>(model.getChildren(study));
    series.sort(DicomSorter.SERIES_COMPARATOR);
    for (MediaSeriesGroup s : series) {
      if (s instanceof DicomSeries dicomSeries && !DicomModel.isHiddenModality(dicomSeries)) {
        return Optional.of(dicomSeries);
      }
    }
    return Optional.empty();
  }

  /**
   * Sorts the visits, most recent day first and by time within a day, under day headers; the
   * visits of a closed day are left out.
   */
  static List<Row> buildRows(List<VisitRow> visits, Predicate<LocalDate> isExpanded) {
    List<VisitRow> sorted = new ArrayList<>(visits);
    sorted.sort(VISIT_ORDER);
    List<Row> rows = new ArrayList<>();
    int i = 0;
    while (i < sorted.size()) {
      LocalDate day = sorted.get(i).date();
      int end = i;
      while (end < sorted.size() && Objects.equals(sorted.get(end).date(), day)) {
        end++;
      }
      boolean expanded = isExpanded.test(day);
      rows.add(new DayRow(day, end - i, expanded));
      if (expanded) {
        rows.addAll(sorted.subList(i, end));
      }
      i = end;
    }
    return rows;
  }

  private List<VisitRow> collectVisits(String query) {
    DicomModel model = explorer.getDataExplorerModel();
    List<VisitRow> visits = new ArrayList<>();
    for (int i = 0; i < source.getSize(); i++) {
      if (source.getElementAt(i) instanceof MediaSeriesGroup patient
          && (query.isEmpty() || patient.toString().toLowerCase(Locale.ROOT).contains(query))) {
        for (MediaSeriesGroup study : model.getChildren(patient)) {
          visits.add(
              new VisitRow(
                  patient,
                  study,
                  TagD.getTagValue(study, Tag.StudyDate, LocalDate.class),
                  TagD.getTagValue(study, Tag.StudyTime, LocalTime.class)));
        }
      }
    }
    return visits;
  }

  private void refresh() {
    Row selected = list.getSelectedValue();
    String query = search.getText().strip().toLowerCase(Locale.ROOT);
    List<VisitRow> visits = collectVisits(query);
    LocalDate latest =
        visits.stream().map(VisitRow::date).filter(Objects::nonNull).max(LocalDate::compareTo)
            .orElse(null);
    // While searching every day is open, so that no match is hidden
    List<Row> rows =
        buildRows(
            visits,
            day ->
                !query.isEmpty()
                    || toggledDays.getOrDefault(day, Objects.equals(day, latest)));
    adjusting = true;
    try {
      shown.clear();
      shown.addAll(rows);
      int index = selected instanceof VisitRow v ? indexOfStudy(v.study()) : -1;
      if (index >= 0) {
        list.setSelectedIndex(index);
      }
    } finally {
      adjusting = false;
    }
    int total = query.isEmpty() ? visits.size() : collectVisits("").size();
    count.setText(
        query.isEmpty()
            ? PatientListMessages.format("count.all", total)
            : PatientListMessages.format("count", visits.size(), total));
    Optional<String> folder = FolderWatcher.getWatchedFolder().map(Object::toString);
    watched.setVisible(folder.isPresent());
    folder.ifPresent(
        f -> {
          watched.setText(PatientListMessages.format("watching", f));
          watched.setToolTipText(f);
        });
  }

  private int indexOfStudy(MediaSeriesGroup study) {
    for (int i = 0; i < shown.size(); i++) {
      if (shown.get(i) instanceof VisitRow v && v.study() == study) {
        return i;
      }
    }
    return -1;
  }

  /** Moves keyboard focus to the list, so that the arrow keys walk through the visits. */
  public void focusList() {
    list.requestFocusInWindow();
  }

  @Override
  public void patientSelected(MediaSeriesGroup patient) {
    if (patient == null
        || list.getSelectedValue() instanceof VisitRow v && v.patient() == patient) {
      return;
    }
    for (int i = 0; i < shown.size(); i++) {
      if (shown.get(i) instanceof VisitRow v && v.patient() == patient) {
        adjusting = true;
        try {
          list.setSelectedIndex(i);
          list.ensureIndexIsVisible(i);
        } finally {
          adjusting = false;
        }
        return;
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

  private static String titleOf(LocalDate date) {
    if (date == null) {
      return PatientListMessages.getString("day.none");
    }
    String formatted = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT));
    LocalDate today = LocalDate.now();
    if (date.equals(today)) {
      return PatientListMessages.format("day.today", formatted);
    }
    if (date.equals(today.minusDays(1))) {
      return PatientListMessages.format("day.yesterday", formatted);
    }
    String weekday =
        date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())); // NON-NLS
    return Character.toUpperCase(weekday.charAt(0)) + weekday.substring(1) + " · " + formatted;
  }

  private static String details(VisitRow visit) {
    StringJoiner line = new StringJoiner("  ·  "); // NON-NLS
    if (visit.time() != null) {
      line.add(visit.time().format(DateTimeFormatter.ofPattern("HH:mm"))); // NON-NLS
    }
    LocalDate birth = TagD.getTagValue(visit.patient(), Tag.PatientBirthDate, LocalDate.class);
    if (birth != null) {
      // Screening systems often know the birth year only and store it as January 1st
      line.add(
          birth.getDayOfYear() == 1
              ? PatientListMessages.format("born.year", String.valueOf(birth.getYear()))
              : PatientListMessages.format(
                  "born", birth.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))));
    }
    String sex = TagD.getTagValue(visit.patient(), Tag.PatientSex, String.class);
    if (sex != null && !sex.isBlank()) {
      line.add(PatientListMessages.getString("sex." + sex.strip().toUpperCase(Locale.ROOT)));
    }
    return line.toString();
  }

  private static class RowRenderer implements ListCellRenderer<Row> {
    private final JPanel visitCell = new JPanel(new BorderLayout(0, 1));
    private final JLabel name = new JLabel();
    private final JLabel info = new JLabel();
    private final JPanel dayCell = new JPanel(new BorderLayout(8, 0));
    private final JLabel dayTitle = new JLabel();
    private final JLabel dayCount = new JLabel();

    RowRenderer() {
      visitCell.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 6));
      name.setFont(name.getFont().deriveFont(Font.BOLD, name.getFont().getSize2D() + 1f));
      info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
      visitCell.add(name, BorderLayout.NORTH);
      visitCell.add(info, BorderLayout.SOUTH);

      dayCell.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createMatteBorder(
                  1, 0, 1, 0, UIManager.getColor("Component.borderColor")),
              BorderFactory.createEmptyBorder(5, 6, 5, 8)));
      dayTitle.setFont(dayTitle.getFont().deriveFont(Font.BOLD));
      dayCount.setFont(dayCount.getFont().deriveFont(Font.BOLD));
      dayCell.add(dayTitle, BorderLayout.CENTER);
      dayCell.add(dayCount, BorderLayout.EAST);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends Row> jList, Row row, int index, boolean isSelected, boolean hasFocus) {
      if (row instanceof DayRow day) {
        Color accent = UIManager.getColor("Component.accentColor");
        Color header = UIManager.getColor("TableHeader.background");
        dayCell.setBackground(
            isSelected
                ? jList.getSelectionBackground()
                : header != null ? header : jList.getBackground());
        Color title =
            isSelected
                ? jList.getSelectionForeground()
                : accent != null ? accent : jList.getForeground();
        dayTitle.setForeground(title);
        dayCount.setForeground(title);
        dayTitle.setText((day.expanded() ? "▾  " : "▸  ") + titleOf(day.date()));
        dayCount.setText(String.valueOf(day.visits()));
        return dayCell;
      }
      VisitRow visit = (VisitRow) row;
      name.setText(visit.patient().toString());
      info.setText(details(visit));
      visitCell.setBackground(isSelected ? jList.getSelectionBackground() : jList.getBackground());
      name.setForeground(isSelected ? jList.getSelectionForeground() : jList.getForeground());
      info.setForeground(
          isSelected
              ? jList.getSelectionForeground()
              : UIManager.getColor("Label.disabledForeground"));
      return visitCell;
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
      refreshLater.restart();
    }

    @Override
    public void intervalRemoved(ListDataEvent e) {
      refreshLater.restart();
    }

    @Override
    public void contentsChanged(ListDataEvent e) {
      refreshLater.restart();
    }
  }
}
