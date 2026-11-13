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
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.miginfocom.swing.MigLayout;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.media.data.MediaSeries.MEDIA_POSITION;
import org.weasis.core.api.util.ThreadUtil;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Catalog;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Entry;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.Visit;
import org.weasis.dicom.explorer.proscan.ProScanForms.Form;

/**
 * Report of the ProScan image shown in the viewer: the description, the doctor and the conclusion
 * that ProScan keeps for it, read from and saved into the database of the fluorograph. Hidden for
 * any other image.
 */
public class ProScanReportPanel extends JPanel {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProScanReportPanel.class);

  // Shared with the database browser: one request at a time to the database
  static final ExecutorService EXECUTOR =
      ThreadUtil.newSingleThreadDaemonExecutor("ProScan report"); // NON-NLS
  static final ProScanDatabase DATABASE = new ProScanDatabase();
  private static final Entry NONE = new Entry(0, ProScanMessages.getString("none"));

  private final Runnable openNext;
  private final JLabel status = new JLabel();
  private final JComboBox<Entry> doctor = new JComboBox<>();
  private final JComboBox<Entry> diagnosis = new JComboBox<>();
  private final JTextArea text = new JTextArea(4, 20);
  private final JButton save = new JButton(ProScanMessages.getString("save"));
  private final JButton saveNext = new JButton(ProScanMessages.getString("save.next"));
  private final JButton forms = new JButton(ProScanMessages.getString("forms.button"));
  private final JButton print = new JButton(ProScanMessages.getString("print.button"));

  /** The patient and the examination, as printed on the result. */
  public record Patient(String name, LocalDate birth, LocalDate date, LocalTime time) {}

  private Visit visit;
  private Patient patient;
  private String patientName;
  private boolean dirty;
  private boolean filling;
  private int generation;

  /**
   * @param openNext opens the next visit of the list, after a "save and next"
   */
  public ProScanReportPanel(Runnable openNext) {
    this.openNext = openNext;
    setLayout(new MigLayout("fillx, ins 6 4 4 4, gap 4 4", "[][grow,fill]", "")); // NON-NLS
    setBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")));

    JLabel title = new JLabel(ProScanMessages.getString("title"));
    title.setFont(title.getFont().deriveFont(Font.BOLD));
    status.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    add(title, "span, split 2"); // NON-NLS
    add(status, "growx, wmin 0"); // NON-NLS

    for (JComboBox<Entry> combo : List.of(doctor, diagnosis)) {
      // A long conclusion must not widen the explorer
      combo.setPrototypeDisplayValue(new Entry(0, "XXXXXXXXXXXXXXX")); // NON-NLS
      combo.addActionListener(_ -> markDirty());
    }
    add(new JLabel(ProScanMessages.getString("doctor")), "newline"); // NON-NLS
    add(doctor, "growx, wmin 0"); // NON-NLS
    add(new JLabel(ProScanMessages.getString("diagnosis")), "newline"); // NON-NLS
    add(diagnosis, "growx, wmin 0"); // NON-NLS

    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, ProScanMessages.getString("placeholder"));
    text.getDocument().addDocumentListener(new DirtyOnEdit());
    for (JButton b : List.of(forms, print)) {
      b.putClientProperty(
          FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    }
    forms.setToolTipText(ProScanMessages.getString("forms.tip"));
    forms.addActionListener(_ -> showForms());
    print.setToolTipText(ProScanMessages.getString("print.tip"));
    print.addActionListener(_ -> printReport());
    add(forms, "newline, span, split 2"); // NON-NLS
    add(print);
    add(new JScrollPane(text), "newline, span, growx, wmin 0, height 60lp:90lp:"); // NON-NLS

    save.addActionListener(_ -> save(false));
    saveNext.addActionListener(_ -> save(true));
    saveNext.putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
    saveNext.setToolTipText(ProScanMessages.getString("save.tip"));
    save.setToolTipText(ProScanMessages.getString("save.tip"));
    add(saveNext, "newline, span, split 2, right"); // NON-NLS
    add(save);

    bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), false);
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), true);
    setEditable(false);
    setVisible(false);
  }

  private void bind(KeyStroke key, boolean next) {
    String name = next ? "proscan.saveNext" : "proscan.save"; // NON-NLS
    getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
    text.getInputMap().put(key, name);
    AbstractAction action =
        new AbstractAction() {
          @Override
          public void actionPerformed(ActionEvent e) {
            if (save.isEnabled()) {
              save(next);
            }
          }
        };
    getActionMap().put(name, action);
    text.getActionMap().put(name, action);
  }

  /**
   * Shows the report of the first image of the series, or hides the panel when it is not a ProScan
   * image. An edited report of the previous visit is offered for saving first.
   */
  public void setVisit(DicomSeries series, Patient who) {
    if (dirty && visit != null) {
      int answer =
          JOptionPane.showConfirmDialog(
              this,
              ProScanMessages.format("unsaved", patientName),
              ProScanMessages.getString("unsaved.title"),
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE);
      if (answer == JOptionPane.YES_OPTION) {
        submitSave(false);
      }
    }
    dirty = false;
    visit = null;
    patient = who;
    patientName = who == null ? "" : who.name();
    int current = ++generation;
    Optional<Path> image = proScanImage(series);
    if (image.isEmpty() || !ProScanDatabase.isSupported()) {
      setVisible(false);
      return;
    }
    fill(null, null);
    setEditable(false);
    setStatus(ProScanMessages.getString("status.loading"), false);
    setVisible(true);
    EXECUTOR.execute(() -> load(image.get(), current));
  }

  /** The local file of a ProScan image, which ProScan names in its database. */
  static Optional<Path> proScanImage(DicomSeries series) {
    if (series == null) {
      return Optional.empty();
    }
    String manufacturer = TagD.getTagValue(series, Tag.Manufacturer, String.class);
    if (manufacturer == null || !manufacturer.contains("Rentgenprom")) { // NON-NLS
      return Optional.empty();
    }
    DicomImageElement image = series.getMedia(MEDIA_POSITION.FIRST, null, null);
    URI uri = image == null ? null : image.getMediaURI();
    if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) { // NON-NLS
      return Optional.empty();
    }
    try {
      return Optional.of(Path.of(uri));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private void load(Path image, int current) {
    try {
      Optional<Visit> found = DATABASE.read(image);
      if (found.isEmpty()) {
        String key =
            ProScanDatabase.hasDatabase(image) ? "status.norecord" : "status.nodb"; // NON-NLS
        GuiExecutor.execute(
            () -> {
              if (current == generation) {
                setStatus(ProScanMessages.getString(key), true);
              }
            });
        return;
      }
      Catalog catalog = DATABASE.catalog(found.get().database());
      GuiExecutor.execute(
          () -> {
            if (current == generation) {
              visit = found.get();
              fill(found.get(), catalog);
              boolean copy = found.get().archiveCopy();
              setEditable(!copy);
              setStatus(ProScanMessages.getString(copy ? "status.archive" : "status.loaded"), copy);
            }
          });
    } catch (Exception e) {
      LOGGER.error("Reading the ProScan report of {}", image, e);
      GuiExecutor.execute(
          () -> {
            if (current == generation) {
              setStatus(ProScanMessages.format("status.error", e.getMessage()), true);
            }
          });
    }
  }

  private void save(boolean next) {
    if (visit == null) {
      return;
    }
    submitSave(next);
  }

  private void submitSave(boolean next) {
    Visit target = visit;
    String value = text.getText();
    int doctorId = idOf(doctor);
    int diagnosisId = idOf(diagnosis);
    int current = generation;
    setEditable(false);
    setStatus(ProScanMessages.getString("status.saving"), false);
    EXECUTOR.execute(
        () -> {
          try {
            DATABASE.write(target, value, doctorId, diagnosisId);
            String time =
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")); // NON-NLS
            GuiExecutor.execute(
                () -> {
                  if (current == generation) {
                    dirty = false;
                    setEditable(true);
                    setStatus(ProScanMessages.format("status.saved", time), false);
                    if (next) {
                      openNext.run();
                    }
                  }
                });
          } catch (Exception e) {
            LOGGER.error("Saving the ProScan report of {}", target.fileName(), e);
            GuiExecutor.execute(
                () -> {
                  if (current == generation) {
                    setEditable(true);
                    setStatus(ProScanMessages.format("status.error", e.getMessage()), true);
                  } else {
                    JOptionPane.showMessageDialog(
                        this,
                        ProScanMessages.format("status.error", e.getMessage()),
                        ProScanMessages.getString("title"),
                        JOptionPane.ERROR_MESSAGE);
                  }
                });
          }
        });
  }

  private void fill(Visit record, Catalog catalog) {
    filling = true;
    try {
      fillCombo(doctor, catalog == null ? List.of() : catalog.doctors(), record, true);
      fillCombo(diagnosis, catalog == null ? List.of() : catalog.diagnoses(), record, false);
      text.setText(record == null ? "" : record.text());
      text.setCaretPosition(0);
    } finally {
      filling = false;
    }
    dirty = false;
  }

  private static void fillCombo(
      JComboBox<Entry> combo, List<Entry> entries, Visit record, boolean doctors) {
    DefaultComboBoxModel<Entry> model = new DefaultComboBoxModel<>();
    model.addElement(NONE);
    entries.forEach(model::addElement);
    int id = record == null ? 0 : doctors ? record.doctorId() : record.diagnosisId();
    Entry selected = entries.stream().filter(e -> e.id() == id).findFirst().orElse(NONE);
    if (selected == NONE && id != 0) {
      // Keep a value missing from the dictionary, so that saving does not erase it
      selected = new Entry(id, "#" + id);
      model.addElement(selected);
    }
    model.setSelectedItem(selected);
    combo.setModel(model);
  }

  private static int idOf(JComboBox<Entry> combo) {
    return combo.getSelectedItem() instanceof Entry entry ? entry.id() : 0;
  }

  private void showForms() {
    JPopupMenu menu = new JPopupMenu();
    for (Form form : ProScanForms.load()) {
      JMenuItem item = new JMenuItem(form.name());
      item.addActionListener(_ -> applyForm(form));
      menu.add(item);
    }
    if (menu.getComponentCount() > 0) {
      menu.addSeparator();
    }
    JMenuItem edit = new JMenuItem(ProScanMessages.getString("forms.edit"));
    edit.addActionListener(_ -> ProScanFormEditor.open(this, conclusionNames()));
    menu.add(edit);
    menu.show(forms, 0, forms.getHeight());
  }

  private List<String> conclusionNames() {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < diagnosis.getItemCount(); i++) {
      Entry entry = diagnosis.getItemAt(i);
      if (entry != NONE && !names.contains(entry.name())) {
        names.add(entry.name());
      }
    }
    return names;
  }

  private void applyForm(Form form) {
    String current = text.getText().strip();
    ProScanFormFill.fill(this, form, !current.isEmpty())
        .ifPresent(
            result -> {
              text.setText(
                  result.replace() || current.isEmpty()
                      ? result.text()
                      : current + " " + result.text());
              List<Entry> entries = new ArrayList<>();
              for (int i = 0; i < diagnosis.getItemCount(); i++) {
                entries.add(diagnosis.getItemAt(i));
              }
              ProScanForms.matchConclusion(form, entries).ifPresent(diagnosis::setSelectedItem);
              text.requestFocusInWindow();
            });
  }

  private void printReport() {
    Patient p = patient == null ? new Patient(patientName, null, null, null) : patient;
    ProScanPrint.print(
        this,
        List.of(
            new ProScanPrint.Sheet(
                p.name(),
                p.birth(),
                p.date(),
                p.time(),
                idOf(doctor) == 0 ? "" : String.valueOf(doctor.getSelectedItem()),
                idOf(diagnosis) == 0 ? "" : String.valueOf(diagnosis.getSelectedItem()),
                text.getText())));
  }

  private void setEditable(boolean editable) {
    forms.setEnabled(editable);
    print.setEnabled(visit != null);
    text.setEditable(editable);
    doctor.setEnabled(editable);
    diagnosis.setEnabled(editable);
    save.setEnabled(editable);
    saveNext.setEnabled(editable);
  }

  private void setStatus(String message, boolean error) {
    status.setText(message);
    status.setToolTipText(message);
    Color color =
        error
            ? UIManager.getColor("Component.error.focusedBorderColor")
            : UIManager.getColor("Label.disabledForeground");
    status.setForeground(color != null ? color : error ? Color.RED : Color.GRAY);
  }

  private void markDirty() {
    if (!filling && visit != null) {
      dirty = true;
      if (status.getText().startsWith("✓")) {
        status.setText("");
      }
    }
  }

  private class DirtyOnEdit implements DocumentListener {
    @Override
    public void insertUpdate(DocumentEvent e) {
      markDirty();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      markDirty();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      markDirty();
    }
  }
}
