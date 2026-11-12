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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
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
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
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
import org.weasis.dicom.explorer.LocalPersistence;
import org.weasis.dicom.explorer.StudySources;

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

  /** A study of a patient, the unit that is opened, with the name of the source it came from. */
  record VisitRow(
      MediaSeriesGroup patient,
      MediaSeriesGroup study,
      LocalDate date,
      LocalTime time,
      String source)
      implements Row {}

  /** How much is shown for each visit. */
  enum Density {
    COMPACT,
    NORMAL,
    DETAILED
  }

  private static final String P_DENSITY = "patient.list.density"; // NON-NLS

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
  private final JComboBox<String> sourceFilter = new JComboBox<>();
  private final JButton viewButton = new JButton(PatientListMessages.getString("view"));
  private Density density = loadDensity();
  private boolean manySources;
  private final Timer refreshLater = new Timer(400, _ -> refresh());
  // Days the user opened or closed; the other days follow the default (only the latest is open)
  private final Map<LocalDate, Boolean> toggledDays = new HashMap<>();
  private boolean adjusting;

  public PatientListPanel(DicomExplorer explorer, ListModel<Object> patients) {
    this.explorer = Objects.requireNonNull(explorer);
    this.source = Objects.requireNonNull(patients);
    setLayout(
        new MigLayout(
            "fill, ins 2 1 2 1", "[grow,fill]", "[][]2[]2[grow,fill]2[]")); // NON-NLS

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

    viewButton.setToolTipText(PatientListMessages.getString("view.tip"));
    viewButton.addActionListener(_ -> showViewMenu());
    sourceFilter.addActionListener(
        _ -> {
          if (!adjusting) {
            refresh();
          }
        });
    sourceFilter.setVisible(false);

    JScrollPane scroll = new JScrollPane(list);
    scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));
    JPanel searchBar = new JPanel(new MigLayout("ins 0, fillx", "[grow,fill]2[]")); // NON-NLS
    searchBar.add(search, "growx"); // NON-NLS
    searchBar.add(viewButton);
    add(searchBar, "growx"); // NON-NLS
    add(sourceFilter, "newline, growx, hidemode 3"); // NON-NLS
    add(count, "newline, growx"); // NON-NLS
    add(scroll, "newline, grow, height 80lp:360lp:"); // NON-NLS
    add(watched, "newline, growx, hidemode 3"); // NON-NLS

    refreshLater.setRepeats(false);
    FolderWatcher.addChangeListener(() -> GuiExecutor.execute(refreshLater::restart));
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

  private static Density loadDensity() {
    try {
      return Density.valueOf(
          LocalPersistence.getProperties().getProperty(P_DENSITY, Density.NORMAL.name()));
    } catch (IllegalArgumentException e) {
      return Density.NORMAL;
    }
  }

  private void showViewMenu() {
    JPopupMenu menu = new JPopupMenu();
    ButtonGroup group = new ButtonGroup();
    for (Density d : Density.values()) {
      JRadioButtonMenuItem item =
          new JRadioButtonMenuItem(
              PatientListMessages.getString("view." + d.name().toLowerCase(Locale.ROOT)),
              d == density);
      item.addActionListener(
          _ -> {
            density = d;
            LocalPersistence.getProperties().setProperty(P_DENSITY, d.name());
            refresh();
          });
      group.add(item);
      menu.add(item);
    }
    menu.show(viewButton, 0, viewButton.getHeight());
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

  private List<VisitRow> collectVisits(String query, String sourceOnly) {
    DicomModel model = explorer.getDataExplorerModel();
    String local = PatientListMessages.getString("source.local");
    List<VisitRow> visits = new ArrayList<>();
    for (int i = 0; i < source.getSize(); i++) {
      if (source.getElementAt(i) instanceof MediaSeriesGroup patient
          && (query.isEmpty() || patient.toString().toLowerCase(Locale.ROOT).contains(query))) {
        for (MediaSeriesGroup study : model.getChildren(patient)) {
          String studyUid = TagD.getTagValue(study, Tag.StudyInstanceUID, String.class);
          String from = StudySources.get(studyUid).orElse(local);
          if (sourceOnly == null || sourceOnly.equals(from)) {
            visits.add(
                new VisitRow(
                    patient,
                    study,
                    TagD.getTagValue(study, Tag.StudyDate, LocalDate.class),
                    TagD.getTagValue(study, Tag.StudyTime, LocalTime.class),
                    from));
          }
        }
      }
    }
    return visits;
  }

  private void refresh() {
    Row selected = list.getSelectedValue();
    String query = search.getText().strip().toLowerCase(Locale.ROOT);
    List<VisitRow> all = collectVisits("", null);
    updateSourceFilter(all);
    String sourceOnly =
        sourceFilter.getSelectedIndex() > 0 ? (String) sourceFilter.getSelectedItem() : null;
    List<VisitRow> visits = collectVisits(query, sourceOnly);
    LocalDate latest =
        visits.stream().map(VisitRow::date).filter(Objects::nonNull).max(LocalDate::compareTo)
            .orElse(null);
    // While searching every day is open, so that no match is hidden
    List<Row> rows =
        buildRows(
            visits,
            day ->
                !query.isEmpty()
                    || sourceOnly != null
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
    int total = all.size();
    count.setText(
        visits.size() == total
            ? PatientListMessages.format("count.all", total)
            : PatientListMessages.format("count", visits.size(), total));
    List<Path> folders = FolderWatcher.getWatchedFolders();
    watched.setVisible(!folders.isEmpty());
    if (!folders.isEmpty()) {
      watched.setText(
          PatientListMessages.format(
              "watching",
              String.join(", ", folders.stream().map(FolderWatcher::labelOf).toList())));
      watched.setToolTipText(
          String.join("\n", folders.stream().map(Path::toString).toList()));
    }
  }

  /** Lists the sources in the filter, which is shown only when there is more than one. */
  private void updateSourceFilter(List<VisitRow> all) {
    List<String> sources = all.stream().map(VisitRow::source).distinct().sorted().toList();
    manySources = sources.size() > 1;
    Object selected = sourceFilter.getSelectedItem();
    adjusting = true;
    try {
      sourceFilter.removeAllItems();
      sourceFilter.addItem(PatientListMessages.getString("source.all"));
      sources.forEach(sourceFilter::addItem);
      if (selected != null && sources.contains(selected)) {
        sourceFilter.setSelectedItem(selected);
      } else {
        sourceFilter.setSelectedIndex(0);
      }
    } finally {
      adjusting = false;
    }
    sourceFilter.setVisible(manySources);
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

  private static String timeOf(VisitRow visit) {
    return visit.time() == null
        ? ""
        : visit.time().format(DateTimeFormatter.ofPattern("HH:mm")); // NON-NLS
  }

  private static String details(VisitRow visit) {
    StringJoiner line = new StringJoiner("  ·  "); // NON-NLS
    if (visit.time() != null) {
      line.add(timeOf(visit));
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

  private static String moreDetails(VisitRow visit) {
    StringJoiner line = new StringJoiner("  ·  "); // NON-NLS
    String id = TagD.getTagValue(visit.patient(), Tag.PatientID, String.class);
    if (id != null && !id.isBlank()) {
      line.add("ID " + id.strip()); // NON-NLS
    }
    for (int tag : new int[] {Tag.StudyDescription, Tag.InstitutionName}) {
      String value = TagD.getTagValue(visit.study(), tag, String.class);
      if (value != null && !value.isBlank()) {
        line.add(value.strip());
      }
    }
    return line.toString();
  }

  // Light badge colors, readable with dark text in light and dark themes
  private static final Color[] BADGE_COLORS = {
    new Color(0xCFE3FA),
    new Color(0xD7F0D2),
    new Color(0xFBE3C0),
    new Color(0xEAD8F5),
    new Color(0xF8D3D3),
    new Color(0xD3F1EE)
  };

  static Color badgeColor(String source) {
    return BADGE_COLORS[Math.floorMod(source.hashCode(), BADGE_COLORS.length)];
  }

  private class RowRenderer implements ListCellRenderer<Row> {
    private final JPanel visitCell = new JPanel(new BorderLayout(0, 1));
    private final JPanel top = new JPanel(new BorderLayout(6, 0));
    private final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    private final JLabel name = new JLabel();
    private final JLabel time = new JLabel();
    private final JLabel badge = new JLabel();
    private final JLabel info = new JLabel();
    private final JLabel more = new JLabel();
    private final JPanel dayCell = new JPanel(new BorderLayout(8, 0));
    private final JLabel dayTitle = new JLabel();
    private final JLabel dayCount = new JLabel();

    RowRenderer() {
      name.setFont(name.getFont().deriveFont(Font.BOLD));
      for (JLabel small : new JLabel[] {info, more, time}) {
        small.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
      }
      badge.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
      badge.setOpaque(true);
      badge.setForeground(new Color(0x1B2A3A));
      badge.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
      top.setOpaque(false);
      right.setOpaque(false);
      right.add(badge);
      right.add(time);
      top.add(name, BorderLayout.CENTER);
      top.add(right, BorderLayout.EAST);
      visitCell.add(top, BorderLayout.NORTH);
      visitCell.add(info, BorderLayout.CENTER);
      visitCell.add(more, BorderLayout.SOUTH);

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
        return renderDay(jList, day, isSelected);
      }
      VisitRow visit = (VisitRow) row;
      boolean compact = density == Density.COMPACT;
      int vertical = compact ? 2 : 4;
      visitCell.setBorder(BorderFactory.createEmptyBorder(vertical, 14, vertical, 6));
      name.setText(visit.patient().toString());
      float size = UIManager.getFont("Label.font").getSize2D() + (compact ? 0f : 1f);
      name.setFont(name.getFont().deriveFont(Font.BOLD, size));

      // The time stays on the name line in compact mode, it moves to the details otherwise
      time.setText(compact ? timeOf(visit) : "");
      time.setVisible(compact);
      badge.setText(visit.source());
      badge.setBackground(badgeColor(visit.source()));
      badge.setVisible(manySources);
      info.setText(details(visit));
      info.setVisible(!compact);
      String extra = density == Density.DETAILED ? moreDetails(visit) : "";
      more.setText(extra);
      more.setVisible(!extra.isEmpty());

      Color fg = isSelected ? jList.getSelectionForeground() : jList.getForeground();
      Color dim = isSelected ? fg : UIManager.getColor("Label.disabledForeground");
      visitCell.setBackground(isSelected ? jList.getSelectionBackground() : jList.getBackground());
      name.setForeground(fg);
      time.setForeground(dim);
      info.setForeground(dim);
      more.setForeground(dim);
      return visitCell;
    }

    private Component renderDay(JList<? extends Row> jList, DayRow day, boolean isSelected) {
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
