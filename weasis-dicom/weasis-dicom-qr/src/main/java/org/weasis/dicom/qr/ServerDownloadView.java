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

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import net.miginfocom.swing.MigLayout;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.service.QueryRetrieveLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AbstractItemDialogPage;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.MediaSeriesGroupNode;
import org.weasis.core.api.net.auth.AuthMethod;
import org.weasis.core.api.net.auth.OAuth2ServiceFactory;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.ui.pref.PreferenceDialog;
import org.weasis.core.ui.tp.raven.datetime.DatePicker;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.display.CharsetEncoding;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.imp.ImportDicom;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode.RetrieveType;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode.UsageType;
import org.weasis.dicom.explorer.pref.node.AuthenticationPersistence;
import org.weasis.dicom.explorer.pref.node.DefaultDicomNode;
import org.weasis.dicom.explorer.pref.node.DicomWebNode;
import org.weasis.dicom.explorer.rs.RsQueryParams;
import org.weasis.dicom.explorer.rs.RsQueryResult;
import org.weasis.dicom.op.CFind;
import org.weasis.dicom.param.DicomParam;
import org.weasis.dicom.param.DicomState;
import org.weasis.dicom.tool.DicomListener;

/**
 * Simple download window: a name, a period picked with one click, the archives to look in, then a
 * sortable result table. More criteria and the technical settings are one click away but hidden by
 * default. Each study is retrieved from the archive that returned it.
 */
public class ServerDownloadView extends AbstractItemDialogPage
    implements ImportDicom, Scrollable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerDownloadView.class);

  private static final String P_ARCHIVES = "download.archives"; // NON-NLS
  private static final String P_PERIOD = "download.period"; // NON-NLS
  private static final String P_LIMIT = "download.limit"; // NON-NLS
  private static final String SEPARATOR = "\u001f";
  private static final StudyPeriod[] PERIODS = {
    StudyPeriod.TODAY,
    StudyPeriod.YESTERDAY,
    StudyPeriod.LAST_7_DAYS,
    StudyPeriod.LAST_MONTH,
    StudyPeriod.ALL,
    StudyPeriod.DATE_RANGE
  };
  private static final String[] MODALITIES = {
    "", "CR", "DX", "CT", "MR", "US", "MG", "XA", "RF", "NM", "PT", "OT" // NON-NLS
  };

  private final JTextField name = new JTextField();
  private final JTextField patientId = new JTextField();
  private final JTextField birthDate = new JTextField();
  private final JTextField description = new JTextField();
  private final JComboBox<String> modality = new JComboBox<>(MODALITIES);
  private final JPanel moreCriteria =
      new JPanel(new MigLayout("ins 0, wrap 4", "[][grow,fill]16[][grow,fill]")); // NON-NLS
  private final JToggleButton moreToggle = new JToggleButton();

  private final Map<StudyPeriod, JToggleButton> periodButtons = new EnumMap<>(StudyPeriod.class);
  private final DatePicker fromPicker = buildDatePicker();
  private final DatePicker toPicker = buildDatePicker();
  private final JPanel datesPanel = new JPanel(new MigLayout("ins 0", "[][]8[][]")); // NON-NLS

  private final JPanel archivesPanel = new JPanel(new MigLayout("ins 0, gap 4")); // NON-NLS
  private final Set<String> checkedArchives = new LinkedHashSet<>();
  private final Map<String, JLabel> archiveStatus = new HashMap<>();
  private final JButton checkConnection = new JButton(DownloadMessages.getString("check"));

  private final StudyTableModel studies = new StudyTableModel();
  private final SeriesTableModel series = new SeriesTableModel();
  private final JTable seriesTable = new JTable(series);
  private final JTable studyTable = new JTable(studies);
  private final TableRowSorter<StudyTableModel> sorter = new TableRowSorter<>(studies);
  private final JTextField resultFilter = new JTextField();
  private final JLabel status = new JLabel();
  private final JProgressBar progress = new JProgressBar();
  private final JButton download = new JButton(DownloadMessages.getString("download"));
  private final ExecutorService executor = Executors.newFixedThreadPool(3);

  private volatile MultiArchiveSearch lastSearch;
  private DicomListener dicomListener;

  public ServerDownloadView() {
    super(DownloadMessages.getString("title"), -20);
    setComponentPosition(-20);
    setLayout(new MigLayout("fill, ins 8, gapy 8", "[grow,fill]", "")); // NON-NLS

    add(buildSearchBox(), "growx, wrap"); // NON-NLS
    add(buildResultBox(), "grow, push, wrap"); // NON-NLS
    add(buildStatusBar(), "growx"); // NON-NLS
    restoreState();
  }

  // The import dialog shows its pages in a scroll pane: fill it instead of keeping the
  // preferred size, so that the result table grows with the dialog
  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
    return 16;
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
    return visible.height;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return true;
  }

  // ---------------------------------------------------------------- search box

  private JPanel buildSearchBox() {
    JPanel box =
        new JPanel(new MigLayout("ins 0, fillx, wrap 2, gapy 6", "[right][grow,fill]")); // NON-NLS

    name.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, DownloadMessages.getString("name.hint"));
    name.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    name.putClientProperty(FlatClientProperties.STYLE, "font: +2"); // NON-NLS
    name.addActionListener(_ -> search());
    JButton find = new JButton(DownloadMessages.getString("search"));
    find.putClientProperty(FlatClientProperties.STYLE, "font: +2 bold"); // NON-NLS
    find.addActionListener(_ -> search());
    JPanel nameRow = new JPanel(new MigLayout("ins 0, fillx", "[grow,fill]8[]")); // NON-NLS
    nameRow.add(name, "growx"); // NON-NLS
    nameRow.add(find);
    box.add(new JLabel(DownloadMessages.getString("who")));
    box.add(nameRow, "growx"); // NON-NLS

    box.add(new JLabel(DownloadMessages.getString("when")));
    box.add(buildPeriodRow(), "growx"); // NON-NLS

    checkConnection.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    checkConnection.addActionListener(_ -> checkConnections(true));
    box.add(new JLabel(DownloadMessages.getString("where")));
    box.add(archivesPanel, "growx, split 2"); // NON-NLS
    box.add(checkConnection, "gapleft push"); // NON-NLS

    moreToggle.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    moreToggle.addActionListener(_ -> updateMoreCriteria());
    box.add(new JLabel());
    box.add(moreToggle, "split 2"); // NON-NLS
    JButton settings = new JButton(DownloadMessages.getString("settings"));
    settings.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    settings.addActionListener(_ -> showSettings(settings));
    box.add(settings, "gapleft push"); // NON-NLS

    for (JTextField field : List.of(patientId, birthDate, description)) {
      field.addActionListener(_ -> search());
    }
    birthDate.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, DownloadMessages.getString("birth.hint"));
    moreCriteria.add(new JLabel(DownloadMessages.getString("more.id")));
    moreCriteria.add(patientId);
    moreCriteria.add(new JLabel(DownloadMessages.getString("more.birth")));
    moreCriteria.add(birthDate);
    moreCriteria.add(new JLabel(DownloadMessages.getString("more.description")));
    moreCriteria.add(description);
    moreCriteria.add(new JLabel(DownloadMessages.getString("more.modality")));
    moreCriteria.add(modality);
    modality.setRenderer(
        (list, value, index, isSelected, focus) -> {
          JLabel label =
              new JLabel(
                  value == null || value.isEmpty()
                      ? DownloadMessages.getString("more.any")
                      : value);
          label.setOpaque(isSelected);
          if (isSelected) {
            label.setBackground(list.getSelectionBackground());
            label.setForeground(list.getSelectionForeground());
          }
          label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
          return label;
        });
    box.add(new JLabel());
    box.add(moreCriteria, "growx, hidemode 3"); // NON-NLS
    updateMoreCriteria();
    return box;
  }

  private JPanel buildPeriodRow() {
    JPanel row = new JPanel(new MigLayout("ins 0, gap 4")); // NON-NLS
    ButtonGroup group = new ButtonGroup();
    for (StudyPeriod period : PERIODS) {
      JToggleButton button =
          new JToggleButton(DownloadMessages.getString("period." + period.name()));
      button.addActionListener(
          _ -> {
            datesPanel.setVisible(period == StudyPeriod.DATE_RANGE);
            prefs().setProperty(P_PERIOD, period.name());
          });
      group.add(button);
      periodButtons.put(period, button);
      row.add(button);
    }
    datesPanel.add(new JLabel(DownloadMessages.getString("from")));
    datesPanel.add(fromPicker.getEditor(), "width 110lp!"); // NON-NLS
    datesPanel.add(new JLabel(DownloadMessages.getString("to")));
    datesPanel.add(toPicker.getEditor(), "width 110lp!"); // NON-NLS
    row.add(datesPanel, "gapleft 8, hidemode 3"); // NON-NLS
    return row;
  }

  private static DatePicker buildDatePicker() {
    DatePicker picker = new DatePicker();
    picker.setStartWeekOnMonday(true);
    picker.setEditor(new JFormattedTextField());
    return picker;
  }

  private void updateMoreCriteria() {
    boolean open = moreToggle.isSelected();
    moreCriteria.setVisible(open);
    moreToggle.setText(DownloadMessages.getString(open ? "more.less" : "more.show"));
    revalidate();
  }

  private void reloadArchives() {
    checkedArchives.clear();
    for (String s : prefs().getProperty(P_ARCHIVES, "").split(SEPARATOR)) {
      if (!s.isBlank()) {
        checkedArchives.add(s);
      }
    }
    List<AbstractDicomNode> nodes = ServerWatcher.configuredArchives();
    if (checkedArchives.isEmpty() && !nodes.isEmpty()) {
      checkedArchives.add(nodes.getFirst().getDescription());
    }
    archivesPanel.removeAll();
    archiveStatus.clear();
    for (AbstractDicomNode node : nodes) {
      JCheckBox box =
          new JCheckBox(node.getDescription(), checkedArchives.contains(node.getDescription()));
      box.setToolTipText(addressOf(node));
      box.addActionListener(
          _ -> {
            if (box.isSelected()) {
              checkedArchives.add(node.getDescription());
            } else {
              checkedArchives.remove(node.getDescription());
            }
            prefs().setProperty(P_ARCHIVES, String.join(SEPARATOR, checkedArchives));
          });
      archivesPanel.add(box);
      JLabel dot = new JLabel("\u25CF"); // NON-NLS
      dot.setForeground(UIManager.getColor("Label.disabledForeground"));
      dot.setToolTipText(DownloadMessages.getString("check.unknown"));
      archiveStatus.put(node.getDescription(), dot);
      archivesPanel.add(dot, "gapright 12"); // NON-NLS
    }
    if (nodes.isEmpty()) {
      JButton add = new JButton(DownloadMessages.getString("archives.add"));
      add.addActionListener(_ -> openNodeSettings());
      archivesPanel.add(add);
    }
    archivesPanel.revalidate();
    archivesPanel.repaint();
  }

  private static String addressOf(AbstractDicomNode node) {
    if (node instanceof DefaultDicomNode d) {
      return d.getDicomNode().getHostname() + ":" + d.getDicomNode().getPort();
    }
    return node instanceof DicomWebNode w ? String.valueOf(w.getUrl()) : "";
  }

  /**
   * Checks every configured archive and paints its dot green or red, with the reason. A check that
   * is not interactive skips the archives needing a sign-in, so that opening the window never
   * asks for one.
   */
  private void checkConnections(boolean interactive) {
    List<AbstractDicomNode> nodes = ServerWatcher.configuredArchives();
    if (nodes.isEmpty()) {
      return;
    }
    checkConnection.setEnabled(false);
    archiveStatus
        .values()
        .forEach(
            dot -> {
              dot.setForeground(UIManager.getColor("Label.disabledForeground"));
              dot.setToolTipText(DownloadMessages.getString("check.running"));
            });
    Map<AbstractDicomNode, AuthMethod> auths = new HashMap<>();
    for (AbstractDicomNode node : nodes) {
      if (node instanceof DicomWebNode web) {
        AuthMethod auth = AuthenticationPersistence.getAuthMethod(web.getAuthMethodUid());
        if (interactive || OAuth2ServiceFactory.NO_AUTH.equals(auth)) {
          auth = DicomQrView.resolveAuthMethod(web);
          if (auth != null) {
            auths.put(web, auth);
          }
        }
      }
    }
    DefaultDicomNode calling = callingNode();
    executor.execute(
        () -> {
          int failed = 0;
          for (AbstractDicomNode node : nodes) {
            if (node instanceof DicomWebNode && !auths.containsKey(node)) {
              continue; // Needs a sign-in: checked only when asked
            }
            ConnectionCheck.Result result = ConnectionCheck.check(node, calling, auths.get(node));
            if (!result.ok()) {
              failed++;
            }
            GuiExecutor.execute(() -> showConnection(node, result));
          }
          int bad = failed;
          GuiExecutor.execute(
              () -> {
                checkConnection.setEnabled(true);
                status.setText(
                    bad == 0
                        ? DownloadMessages.getString("check.allok")
                        : DownloadMessages.format("check.failed", bad));
              });
        });
  }

  private void showConnection(AbstractDicomNode node, ConnectionCheck.Result result) {
    JLabel dot = archiveStatus.get(node.getDescription());
    if (dot == null) {
      return;
    }
    if (result.ok()) {
      dot.setForeground(new Color(0x2E9E44));
      dot.setToolTipText(DownloadMessages.format("check.ok", result.millis()));
    } else if (result.hint() != null) {
      dot.setForeground(new Color(0xE0A100));
      dot.setToolTipText(DownloadMessages.format("check.hint", result.hint()));
    } else {
      Color error = UIManager.getColor("Component.error.focusedBorderColor");
      dot.setForeground(error != null ? error : Color.RED);
      dot.setToolTipText(DownloadMessages.format("check.error", result.message()));
    }
  }

  private List<AbstractDicomNode> checkedArchives() {
    return ServerWatcher.configuredArchives().stream()
        .filter(n -> checkedArchives.contains(n.getDescription()))
        .toList();
  }

  // ---------------------------------------------------------------- result box

  private JPanel buildResultBox() {
    studyTable.setRowSorter(sorter);
    studyTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    studyTable.setFillsViewportHeight(true);
    studyTable.setRowHeight(studyTable.getRowHeight() + 6);
    studyTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) {
                loadSeriesOfSelection();
                updateDownloadButton();
              }
            });
    studyTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && studyTable.getSelectedRowCount() > 0) {
              downloadSelection();
            }
          }
        });

    resultFilter.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, DownloadMessages.getString("filter.hint"));
    resultFilter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    resultFilter.getDocument().addDocumentListener(new DocumentAdapter(this::applyResultFilter));

    seriesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    seriesTable.setFillsViewportHeight(true);
    seriesTable.setRowHeight(studyTable.getRowHeight());
    seriesTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) {
                updateDownloadButton();
              }
            });
    JScrollPane seriesScroll = new JScrollPane(seriesTable);
    seriesScroll.setBorder(
        BorderFactory.createTitledBorder(DownloadMessages.getString("series.title")));
    JSplitPane split =
        new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(studyTable), seriesScroll);
    split.setResizeWeight(0.7);
    split.setContinuousLayout(true);
    split.setBorder(null);

    JPanel box =
        new JPanel(new MigLayout("ins 0, fill, wrap 1", "[grow,fill]", "[][grow,fill]")); // NON-NLS
    box.add(resultFilter, "growx"); // NON-NLS
    box.add(split, "grow, height 220lp:360lp:"); // NON-NLS
    return box;
  }

  private JPanel buildStatusBar() {
    progress.setIndeterminate(true);
    progress.setVisible(false);
    download.setEnabled(false);
    download.putClientProperty(FlatClientProperties.STYLE, "font: +1 bold"); // NON-NLS
    download.addActionListener(_ -> downloadSelection());
    // The note wraps to the width of the dialog instead of widening it
    JTextArea storage = new JTextArea(DownloadMessages.getString("storage"));
    storage.setEditable(false);
    storage.setFocusable(false);
    storage.setOpaque(false);
    storage.setLineWrap(true);
    storage.setWrapStyleWord(true);
    storage.setBorder(BorderFactory.createEmptyBorder());
    storage.setToolTipText(DownloadMessages.getString("storage.tip"));
    storage.setForeground(UIManager.getColor("Label.disabledForeground"));
    storage.setFont(UIManager.getFont("small.font"));
    // A long status is cut, and shown whole in its tooltip
    status.addPropertyChangeListener("text", _ -> status.setToolTipText(status.getText()));
    JPanel bar = new JPanel(new MigLayout("ins 0, fillx", "[grow,fill][]8[]")); // NON-NLS
    bar.add(status, "growx, wmin 0"); // NON-NLS
    bar.add(progress, "width 80lp!, hidemode 2"); // NON-NLS
    bar.add(download, "wrap"); // NON-NLS
    bar.add(storage, "span 3, growx, wmin 0"); // NON-NLS
    return bar;
  }

  // ---------------------------------------------------------------- state and settings

  private static WProperties prefs() {
    return DicomQrView.getPersistence();
  }

  private void restoreState() {
    StudyPeriod period;
    try {
      period = StudyPeriod.valueOf(prefs().getProperty(P_PERIOD, StudyPeriod.TODAY.name()));
    } catch (IllegalArgumentException e) {
      period = StudyPeriod.TODAY;
    }
    JToggleButton button =
        periodButtons.getOrDefault(period, periodButtons.get(StudyPeriod.TODAY));
    button.setSelected(true);
    datesPanel.setVisible(period == StudyPeriod.DATE_RANGE);
    reloadArchives();
    status.setText(DownloadMessages.getString("status.ready"));
    // Show at once which archive answers, as the dots of the archive row
    SwingUtilities.invokeLater(() -> checkConnections(false));
  }

  private StudyPeriod selectedPeriod() {
    for (Map.Entry<StudyPeriod, JToggleButton> e : periodButtons.entrySet()) {
      if (e.getValue().isSelected()) {
        return e.getKey();
      }
    }
    return StudyPeriod.TODAY;
  }

  private int limit() {
    try {
      return Integer.parseInt(prefs().getProperty(P_LIMIT, "200"));
    } catch (NumberFormatException e) {
      return 200;
    }
  }

  private void openNodeSettings() {
    PreferenceDialog dialog = new PreferenceDialog(SwingUtilities.getWindowAncestor(this));
    dialog.showPage(org.weasis.dicom.explorer.Messages.getString("DicomNodeListView.node_list"));
    GuiUtils.showCenterScreen(dialog);
    reloadArchives();
  }

  /** The technical settings, kept out of the way: retrieve type, calling node, result limit. */
  private void showSettings(Component invoker) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem nodes = new JMenuItem(DownloadMessages.getString("settings.nodes"));
    nodes.addActionListener(_ -> openNodeSettings());
    menu.add(nodes);
    menu.addSeparator();

    JMenu type = new JMenu(DownloadMessages.getString("settings.type"));
    ButtonGroup typeGroup = new ButtonGroup();
    RetrieveType current = retrieveType();
    for (RetrieveType t : RetrieveType.values()) {
      JRadioButtonMenuItem item = new JRadioButtonMenuItem(t.toString(), t == current);
      item.addActionListener(_ -> prefs().setProperty(DicomQrView.LAST_RETRIEVE_TYPE, t.name()));
      typeGroup.add(item);
      type.add(item);
    }
    menu.add(type);

    JMenu calling = new JMenu(DownloadMessages.getString("settings.calling"));
    ButtonGroup callingGroup = new ButtonGroup();
    DefaultDicomNode currentCalling = callingNode();
    for (AbstractDicomNode node : callingNodes()) {
      JRadioButtonMenuItem item =
          new JRadioButtonMenuItem(
              node.getDescription(),
              currentCalling != null
                  && node.getDescription().equals(currentCalling.getDescription()));
      item.addActionListener(
          _ -> prefs().setProperty(DicomQrView.LAST_CALLING_NODE, node.getDescription()));
      callingGroup.add(item);
      calling.add(item);
    }
    menu.add(calling);

    JMenu limitMenu = new JMenu(DownloadMessages.getString("settings.limit"));
    ButtonGroup limitGroup = new ButtonGroup();
    for (int n : new int[] {50, 200, 1000, 0}) {
      JRadioButtonMenuItem item =
          new JRadioButtonMenuItem(
              n == 0 ? DownloadMessages.getString("settings.nolimit") : String.valueOf(n),
              n == limit());
      item.addActionListener(_ -> prefs().setProperty(P_LIMIT, String.valueOf(n)));
      limitGroup.add(item);
      limitMenu.add(item);
    }
    menu.add(limitMenu);
    menu.show(invoker, 0, invoker.getHeight());
  }

  private static List<AbstractDicomNode> callingNodes() {
    return AbstractDicomNode.loadDicomNodes(
        AbstractDicomNode.Type.DICOM_CALLING, UsageType.RETRIEVE);
  }

  private static DefaultDicomNode callingNode() {
    String last = prefs().getProperty(DicomQrView.LAST_CALLING_NODE);
    DefaultDicomNode first = null;
    for (AbstractDicomNode node : callingNodes()) {
      if (node instanceof DefaultDicomNode n) {
        if (first == null) {
          first = n;
        }
        if (last != null && (last.equals(n.getId()) || last.equals(n.getDescription()))) {
          return n;
        }
      }
    }
    return first;
  }

  private static RetrieveType retrieveType() {
    try {
      return RetrieveType.valueOf(
          prefs().getProperty(DicomQrView.LAST_RETRIEVE_TYPE, RetrieveType.CGET.name()));
    } catch (IllegalArgumentException e) {
      return RetrieveType.CGET;
    }
  }

  // ---------------------------------------------------------------- search

  private List<DicomParam> buildKeys(StudyPeriod.Range range, StudyPeriod period) {
    List<DicomParam> keys =
        new ArrayList<>(StudyPeriod.queryKeys(range, period.needsTimeFilter()));
    String who = name.getText().strip();
    if (!who.isEmpty()) {
      keys.add(new DicomParam(Tag.PatientName, wildcard(who)));
    }
    if (moreCriteria.isVisible()) {
      String id = patientId.getText().strip();
      if (!id.isEmpty()) {
        keys.add(new DicomParam(Tag.PatientID, id));
      }
      String birth = birthDate.getText().strip();
      if (!birth.isEmpty()) {
        keys.add(new DicomParam(Tag.PatientBirthDate, dicomDate(birth)));
      }
      String desc = description.getText().strip();
      if (!desc.isEmpty()) {
        keys.add(new DicomParam(Tag.StudyDescription, "*" + wildcard(desc)));
      }
      String mod = (String) modality.getSelectedItem();
      if (mod != null && !mod.isEmpty()) {
        keys.add(new DicomParam(Tag.ModalitiesInStudy, mod));
      }
    }
    return keys;
  }

  /** Name parts are separated by spaces for the user and by carets in DICOM; prefix match. */
  static String wildcard(String value) {
    String v = value.strip().replaceAll("\\s+", "^"); // NON-NLS
    return v.contains("*") || v.contains("?") ? v : v + "*";
  }

  /** Accepts dd.mm.yyyy, dd/mm/yyyy, a birth year or a DICOM date, returns a DICOM date. */
  static String dicomDate(String value) {
    String digits = value.replaceAll("[^0-9]", ""); // NON-NLS
    if (digits.length() == 8 && (value.contains(".") || value.contains("/"))) {
      return digits.substring(4) + digits.substring(2, 4) + digits.substring(0, 2);
    }
    if (digits.length() == 4) {
      return digits + "0101-" + digits + "1231";
    }
    return digits;
  }

  private void search() {
    List<AbstractDicomNode> selected = checkedArchives();
    if (selected.isEmpty()) {
      status.setText(DownloadMessages.getString("status.noarchive"));
      return;
    }
    StudyPeriod period = selectedPeriod();
    StudyPeriod.Range range =
        period.range(
            LocalDateTime.now(), fromPicker.getSelectedDate(), toPicker.getSelectedDate());
    List<DicomParam> keys = buildKeys(range, period);
    Map<AbstractDicomNode, AuthMethod> auths = new HashMap<>();
    List<AbstractDicomNode> reachable = new ArrayList<>();
    for (AbstractDicomNode archive : selected) {
      if (archive instanceof DicomWebNode web) {
        AuthMethod auth = DicomQrView.resolveAuthMethod(web);
        if (auth == null) {
          continue;
        }
        auths.put(web, auth);
      }
      reachable.add(archive);
    }
    DefaultDicomNode calling = callingNode();
    int limit = limit();
    List<DicomParam> findKeys = new ArrayList<>(keys);
    DicomQrView.addStudyFindParams(findKeys, CharsetEncoding.ISO_IR_192);

    status.setText(DownloadMessages.getString("status.searching"));
    progress.setVisible(true);
    studies.setRows(List.of());
    series.setRows(List.of());
    executor.execute(
        () -> {
          MultiArchiveSearch search = new MultiArchiveSearch(auths);
          search.run(
              reachable,
              node -> {
                if (calling == null) {
                  throw new IllegalStateException(
                      Messages.getString("RetrieveTask.no_calling_node"));
                }
                return MultiArchiveSearch.DimseAnswer.of(
                    CFind.process(
                        DicomQrView.buildFindParams(false),
                        calling.getDicomNodeWithOnlyAET(),
                        node.getDicomNode(),
                        limit,
                        QueryRetrieveLevel.STUDY,
                        findKeys.toArray(new DicomParam[0])));
              },
              target -> {
                Properties props = target.baseProperties();
                if (limit > 0) {
                  props.setProperty(
                      RsQueryParams.P_PAGE_EXT, "&limit=%d&offset=0".formatted(limit)); // NON-NLS
                }
                return new RsQuery(
                    target.model(),
                    props,
                    new ArrayList<>(keys),
                    target.authMethod(),
                    target.node().getHeaders());
              });
          List<StudyRow> rows = rowsOf(search, period.needsTimeFilter() ? range : null);
          GuiExecutor.execute(() -> showResult(search, rows));
        });
  }

  private static List<StudyRow> rowsOf(MultiArchiveSearch search, StudyPeriod.Range timeFilter) {
    DicomModel model = search.getModel();
    List<StudyRow> rows = new ArrayList<>();
    for (MediaSeriesGroup patient : model.getChildren(MediaSeriesGroupNode.rootNode)) {
      for (MediaSeriesGroup study : model.getChildren(patient)) {
        String uid = TagD.getTagValue(study, Tag.StudyInstanceUID, String.class);
        LocalDateTime when = TagD.dateTime(Tag.StudyDate, Tag.StudyTime, study);
        if (timeFilter == null || timeFilter.contains(when)) {
          rows.add(new StudyRow(patient, study, uid, when, search.getArchive(uid).orElse(null)));
        }
      }
    }
    // Most recent first, undated last
    rows.sort(
        (a, b) -> {
          if (a.when() == null || b.when() == null) {
            return a.when() == null ? (b.when() == null ? 0 : 1) : -1;
          }
          return b.when().compareTo(a.when());
        });
    return rows;
  }

  private void showResult(MultiArchiveSearch search, List<StudyRow> rows) {
    lastSearch = search;
    progress.setVisible(false);
    studies.setRows(rows);
    String text =
        rows.isEmpty()
            ? DownloadMessages.getString("status.none")
            : DownloadMessages.format("status.found", rows.size());
    Map<AbstractDicomNode, String> failures = search.getFailures();
    if (!failures.isEmpty()) {
      String names =
          String.join(
              ", ", failures.keySet().stream().map(AbstractDicomNode::getDescription).toList());
      text += "   ⚠ " + DownloadMessages.format("status.unreachable", names);
      status.setToolTipText(String.join("\n", failures.values()));
    } else {
      status.setToolTipText(null);
    }
    status.setText(text);
  }

  private void applyResultFilter() {
    String text = resultFilter.getText().strip();
    sorter.setRowFilter(
        text.isEmpty() ? null : RowFilter.regexFilter("(?iu)" + Pattern.quote(text))); // NON-NLS
  }

  // ---------------------------------------------------------------- series

  private void updateDownloadButton() {
    int studiesSelected = studyTable.getSelectedRowCount();
    int seriesSelected = seriesTable.getSelectedRowCount();
    download.setEnabled(studiesSelected > 0);
    if (studiesSelected == 1 && seriesSelected > 0) {
      download.setText(DownloadMessages.format("download.series", seriesSelected));
    } else if (studiesSelected > 1) {
      download.setText(DownloadMessages.format("download.n", studiesSelected));
    } else {
      download.setText(DownloadMessages.getString("download"));
    }
  }

  /** Lists the series of the selected study; nothing when several studies are selected. */
  private void loadSeriesOfSelection() {
    series.setRows(List.of());
    List<StudyRow> selected = selectedStudies();
    MultiArchiveSearch search = lastSearch;
    if (selected.size() != 1 || selected.getFirst().archive() == null || search == null) {
      return;
    }
    StudyRow study = selected.getFirst();
    executor.execute(
        () -> {
          List<SeriesRow> rows = new ArrayList<>();
          for (Attributes a : querySeries(study, search)) {
            rows.add(
                new SeriesRow(
                    study.uid(),
                    a.getString(Tag.SeriesInstanceUID),
                    a.getString(Tag.SeriesNumber, ""),
                    a.getString(Tag.Modality, ""),
                    a.getString(Tag.SeriesDescription, ""),
                    a.getString(Tag.NumberOfSeriesRelatedInstances, "")));
          }
          rows.sort(Comparator.comparing(SeriesRow::sortNumber));
          GuiExecutor.execute(
              () -> {
                List<StudyRow> now = selectedStudies();
                if (now.size() == 1 && now.getFirst() == study) {
                  series.setRows(rows);
                }
              });
        });
  }

  private static List<Attributes> querySeries(StudyRow study, MultiArchiveSearch search) {
    try {
      if (study.archive() instanceof DefaultDicomNode node) {
        DefaultDicomNode calling = callingNode();
        if (calling == null) {
          return List.of();
        }
        DicomParam[] keys = {
          new DicomParam(Tag.StudyInstanceUID, study.uid()),
          CFind.SeriesInstanceUID,
          CFind.Modality,
          CFind.SeriesNumber,
          CFind.SeriesDescription,
          new DicomParam(Tag.NumberOfSeriesRelatedInstances)
        };
        DicomState state =
            CFind.process(
                DicomQrView.buildFindParams(false),
                calling.getDicomNodeWithOnlyAET(),
                node.getDicomNode(),
                0,
                QueryRetrieveLevel.SERIES,
                keys);
        return state.getDicomRSP() == null ? List.of() : state.getDicomRSP();
      }
      if (study.archive() instanceof DicomWebNode node) {
        String url =
            RsQueryResult.seriesQueryUrl(RetrieveTask.dicomWebRoot(node), study.uid(), null);
        return RsQueryResult.parseJSON(
            url,
            search.getAuthMethod(node),
            RsQueryResult.jsonQueryParameters(node.getHeaders()));
      }
    } catch (Exception e) {
      LOGGER.error("Query the series of study {}", study.uid(), e);
    }
    return List.of();
  }

  // ---------------------------------------------------------------- download

  private List<StudyRow> selectedStudies() {
    List<StudyRow> rows = new ArrayList<>();
    for (int viewRow : studyTable.getSelectedRows()) {
      rows.add(studies.row(studyTable.convertRowIndexToModel(viewRow)));
    }
    return rows;
  }

  private void downloadSelection() {
    MultiArchiveSearch search = lastSearch;
    List<StudyRow> selected = selectedStudies();
    DicomModel explorerModel = explorerModel();
    if (search == null || selected.isEmpty() || explorerModel == null) {
      return;
    }
    RetrieveSelection selection = new RetrieveSelection();
    int[] seriesRows = seriesTable.getSelectedRows();
    if (selected.size() == 1 && seriesRows.length > 0) {
      // Only the series picked below the study
      for (int row : seriesRows) {
        SeriesRow s = series.row(row);
        selection.addSeries(s.studyUid(), s.seriesUid());
      }
    } else {
      selected.forEach(r -> selection.addStudy(r.uid()));
    }
    RetrieveHost host = new Host(search.getModel(), this);
    search
        .splitByArchive(selection)
        .forEach(
            (archive, part) ->
                executor.execute(
                    new RetrieveTask(
                        part,
                        explorerModel,
                        host,
                        new RetrieveTask.RetrieveSource(
                            archive,
                            DicomQrView.findWadoRsNode(archive, this),
                            search.getAuthMethod(archive)))));
    status.setText(DownloadMessages.format("status.downloading", selected.size()));
  }

  private static DicomModel explorerModel() {
    var view =
        GuiUtils.getUICore().getExplorerPlugin(org.weasis.dicom.explorer.main.DicomExplorer.NAME);
    return view != null && view.getDataExplorerModel() instanceof DicomModel m ? m : null;
  }

  private synchronized DicomListener listener() {
    if (dicomListener == null) {
      try {
        dicomListener = new DicomListener(DicomQrView.getSessionTempFolder());
      } catch (RuntimeException e) {
        LOGGER.error("Cannot start the DICOM listener", e);
      }
    }
    return dicomListener;
  }

  private record Host(DicomModel queryModel, ServerDownloadView view) implements RetrieveHost {
    @Override
    public DicomModel getQueryModel() {
      return queryModel;
    }

    @Override
    public DefaultDicomNode getCallingNode() {
      return callingNode();
    }

    @Override
    public RetrieveType getRetrieveType() {
      return retrieveType();
    }

    @Override
    public DicomListener getDicomListener() {
      return getRetrieveType() == RetrieveType.CMOVE ? view.listener() : null;
    }

    @Override
    public Component getDialogParent() {
      return view;
    }
  }

  @Override
  public void importDICOM(DicomModel dicomModel, JProgressBar info) {
    downloadSelection();
  }

  @Override
  public void closeAdditionalWindow() {
    // Every choice is saved as it is made
  }

  @Override
  public void resetToDefaultValues() {
    name.setText("");
    patientId.setText("");
    birthDate.setText("");
    description.setText("");
    modality.setSelectedIndex(0);
    resultFilter.setText("");
    studies.setRows(List.of());
  }

  // ---------------------------------------------------------------- table

  /** One study of the result, with the archive it is retrieved from. */
  record StudyRow(
      MediaSeriesGroup patient,
      MediaSeriesGroup study,
      String uid,
      LocalDateTime when,
      AbstractDicomNode archive) {}

  /** One series of the selected study. */
  record SeriesRow(
      String studyUid,
      String seriesUid,
      String number,
      String modality,
      String description,
      String images) {
    int sortNumber() {
      try {
        return Integer.parseInt(number.strip());
      } catch (NumberFormatException e) {
        return Integer.MAX_VALUE;
      }
    }
  }

  private static final class SeriesTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
      "col.number", "col.modality", "col.description", "col.images" // NON-NLS
    };
    private List<SeriesRow> rows = List.of();

    void setRows(List<SeriesRow> rows) {
      this.rows = List.copyOf(rows);
      fireTableDataChanged();
    }

    SeriesRow row(int index) {
      return rows.get(index);
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return DownloadMessages.getString(COLUMNS[column]);
    }

    @Override
    public Object getValueAt(int rowIndex, int column) {
      SeriesRow r = rows.get(rowIndex);
      return switch (column) {
        case 0 -> r.number();
        case 1 -> r.modality();
        case 2 -> r.description();
        default -> r.images();
      };
    }
  }

  private static String text(MediaSeriesGroup group, int tag) {
    Object value = group.getTagValue(TagD.get(tag));
    if (value instanceof String[] values) {
      return String.join(", ", values);
    }
    if (value instanceof LocalDate date) {
      return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT));
    }
    return value == null ? "" : value.toString().replace('^', ' ').strip();
  }

  private static final class StudyTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
      "col.name", "col.birth", "col.date", "col.modality", "col.description", "col.images",
      "col.id", "col.archive" // NON-NLS
    };
    private List<StudyRow> rows = List.of();

    void setRows(List<StudyRow> rows) {
      this.rows = List.copyOf(rows);
      fireTableDataChanged();
    }

    StudyRow row(int index) {
      return rows.get(index);
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return DownloadMessages.getString(COLUMNS[column]);
    }

    @Override
    public Object getValueAt(int rowIndex, int column) {
      StudyRow r = rows.get(rowIndex);
      return switch (column) {
        case 0 -> text(r.patient(), Tag.PatientName);
        case 1 -> text(r.patient(), Tag.PatientBirthDate);
        case 2 ->
            r.when() == null
                ? text(r.study(), Tag.StudyDate)
                : r.when()
                    .format(
                        DateTimeFormatter.ofLocalizedDateTime(
                            FormatStyle.SHORT, FormatStyle.SHORT));
        case 3 -> text(r.study(), Tag.ModalitiesInStudy);
        case 4 -> text(r.study(), Tag.StudyDescription);
        case 5 -> text(r.study(), Tag.NumberOfStudyRelatedInstances);
        case 6 -> text(r.patient(), Tag.PatientID);
        default ->
            Optional.ofNullable(r.archive()).map(AbstractDicomNode::getDescription).orElse("");
      };
    }
  }

  /** Runs an action on every change of a text field. */
  private record DocumentAdapter(Runnable action) implements DocumentListener {
    @Override
    public void insertUpdate(DocumentEvent e) {
      action.run();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      action.run();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      action.run();
    }
  }
}
