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
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.miginfocom.swing.MigLayout;
import org.weasis.dicom.explorer.proscan.ProScanForms.Field;
import org.weasis.dicom.explorer.proscan.ProScanForms.Form;

/**
 * Builds the report forms: a form has items and each item the answers to pick from, one per line.
 */
public final class ProScanFormEditor extends JDialog {

  private final DefaultListModel<Form> forms = new DefaultListModel<>();
  private final JList<Form> formList = new JList<>(forms);
  private final DefaultListModel<Field> fields = new DefaultListModel<>();
  private final JList<Field> fieldList = new JList<>(fields);
  private final JTextField formName = new JTextField();
  private final JComboBox<String> conclusion = new JComboBox<>();
  private final JTextField fieldLabel = new JTextField();
  private final JTextArea answers = new JTextArea(6, 30);
  private final JTextArea preview = new JTextArea(3, 30);
  private final JPanel formPanel = new JPanel();
  private final JPanel fieldPanel = new JPanel();
  private boolean filling;
  private boolean changed;

  private ProScanFormEditor(Component parent, List<String> conclusions) {
    super(
        SwingUtilities.getWindowAncestor(parent),
        ProScanMessages.getString("forms.title"),
        ModalityType.APPLICATION_MODAL);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    ProScanForms.load().forEach(forms::addElement);

    conclusion.setEditable(true);
    conclusion.addItem("");
    conclusions.forEach(conclusion::addItem);
    conclusion.setPrototypeDisplayValue("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"); // NON-NLS

    formList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    formList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) {
            showForm();
          }
        });
    fieldList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    fieldList.setCellRenderer(
        (list, value, index, selected, focus) -> {
          JLabel label =
              new JLabel(
                  (value.label().isEmpty() ? "?" : value.label())
                      + "  ("
                      + value.answers().size()
                      + ")");
          label.setOpaque(true);
          label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
          label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
          label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
          return label;
        });
    fieldList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) {
            showField();
          }
        });

    DocumentListener onFormEdit = new OnEdit(this::updateForm);
    formName.getDocument().addDocumentListener(onFormEdit);
    conclusion.addActionListener(_ -> updateForm());
    DocumentListener onFieldEdit = new OnEdit(this::updateField);
    fieldLabel.getDocument().addDocumentListener(onFieldEdit);
    answers.getDocument().addDocumentListener(onFieldEdit);
    preview.setEditable(false);
    preview.setLineWrap(true);
    preview.setWrapStyleWord(true);

    JPanel left = new JPanel(new MigLayout("fill, ins 8", "[grow,fill]", "[][grow,fill][]"));
    left.add(new JLabel(ProScanMessages.getString("forms.list")), "wrap"); // NON-NLS
    left.add(new JScrollPane(formList), "grow, wrap"); // NON-NLS
    left.add(button("forms.add", this::addForm), "split 2, growx"); // NON-NLS
    left.add(button("forms.remove", this::removeForm), "growx"); // NON-NLS

    formPanel.setLayout(new MigLayout("fillx, ins 8, gap 6", "[][grow,fill]", "")); // NON-NLS
    formPanel.add(new JLabel(ProScanMessages.getString("forms.name")));
    formPanel.add(formName, "wrap"); // NON-NLS
    formPanel.add(new JLabel(ProScanMessages.getString("forms.conclusion")));
    formPanel.add(conclusion, "wmin 0, wrap"); // NON-NLS

    formPanel.add(new JLabel(ProScanMessages.getString("forms.items")), "span, gaptop 8, wrap");
    JPanel itemButtons = new JPanel(new MigLayout("ins 0, wrap 1, gap 4", "[fill]")); // NON-NLS
    itemButtons.add(button("forms.item.add", this::addField));
    itemButtons.add(button("forms.item.remove", this::removeField));
    itemButtons.add(button("forms.item.up", () -> moveField(-1)));
    itemButtons.add(button("forms.item.down", () -> moveField(1)));
    formPanel.add(new JScrollPane(fieldList), "span, split 2, grow, height 90lp:120lp:");
    formPanel.add(itemButtons, "top, wrap"); // NON-NLS

    fieldPanel.setLayout(new MigLayout("fillx, ins 0, gap 6", "[][grow,fill]", "")); // NON-NLS
    fieldPanel.add(new JLabel(ProScanMessages.getString("forms.item.name")));
    fieldPanel.add(fieldLabel, "wrap"); // NON-NLS
    fieldPanel.add(new JLabel(ProScanMessages.getString("forms.item.answers")), "top"); // NON-NLS
    fieldPanel.add(new JScrollPane(answers), "grow, wrap"); // NON-NLS
    JLabel answersHint =
        new JLabel(ProScanMessages.format("forms.item.hint", ProScanForms.NOTHING));
    answersHint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    answersHint.setForeground(UIManager.getColor("Label.disabledForeground"));
    fieldPanel.add(answersHint, "skip, wmin 0, wrap"); // NON-NLS
    formPanel.add(fieldPanel, "span, growx, wrap"); // NON-NLS

    formPanel.add(new JLabel(ProScanMessages.getString("forms.preview")), "span, gaptop 8, wrap");
    formPanel.add(new JScrollPane(preview), "span, growx"); // NON-NLS

    JSplitPane split =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(formPanel));
    split.setDividerLocation(220);

    JLabel where =
        new JLabel(ProScanMessages.format("forms.where", ProScanForms.location().toString()));
    where.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); // NON-NLS
    where.setForeground(UIManager.getColor("Label.disabledForeground"));
    JButton save = button("forms.save", this::saveAndClose);
    JButton cancel = button("export.close", this::dispose);
    JPanel bottom = new JPanel(new MigLayout("fillx, ins 6 8 8 8", "[grow,fill][][]")); // NON-NLS
    bottom.add(where, "wmin 0"); // NON-NLS
    bottom.add(save);
    bottom.add(cancel);

    JPanel content = new JPanel(new MigLayout("fill, ins 0", "[grow,fill]", "[grow,fill][]"));
    content.add(split, "grow, wrap"); // NON-NLS
    content.add(bottom, "growx"); // NON-NLS
    setContentPane(content);
    setSize(new Dimension(860, 600));
    setLocationRelativeTo(parent);
    if (!forms.isEmpty()) {
      formList.setSelectedIndex(0);
    }
    showForm();
  }

  /** Opens the constructor; the conclusions of ProScan, if known, are offered for the forms. */
  public static void open(Component parent, List<String> conclusions) {
    new ProScanFormEditor(parent, conclusions).setVisible(true);
  }

  private static JButton button(String key, Runnable action) {
    JButton button = new JButton(ProScanMessages.getString(key));
    button.addActionListener(_ -> action.run());
    return button;
  }

  private void showForm() {
    Form form = formList.getSelectedValue();
    filling = true;
    try {
      formName.setText(form == null ? "" : form.name());
      conclusion.setSelectedItem(form == null ? "" : form.conclusion());
      fields.clear();
      if (form != null) {
        form.fields().forEach(fields::addElement);
      }
    } finally {
      filling = false;
    }
    setEnabled(formPanel, form != null);
    if (!fields.isEmpty()) {
      fieldList.setSelectedIndex(0);
    }
    showField();
    updatePreview();
  }

  private void showField() {
    Field field = fieldList.getSelectedValue();
    filling = true;
    try {
      fieldLabel.setText(field == null ? "" : field.label());
      answers.setText(field == null ? "" : ProScanForms.toEditorText(field.answers()));
    } finally {
      filling = false;
    }
    setEnabled(fieldPanel, field != null);
  }

  private static void setEnabled(JPanel panel, boolean enabled) {
    for (Component c : panel.getComponents()) {
      c.setEnabled(enabled);
      if (c instanceof JScrollPane scroll) {
        scroll.getViewport().getView().setEnabled(enabled);
      }
    }
  }

  private void updateForm() {
    int index = formList.getSelectedIndex();
    if (filling || index < 0) {
      return;
    }
    Object c = conclusion.getEditor().getItem();
    Form form =
        new Form(
            formName.getText(),
            c == null ? "" : c.toString(),
            Collections.list(fields.elements()));
    filling = true;
    try {
      forms.set(index, form);
    } finally {
      filling = false;
    }
    changed = true;
    updatePreview();
  }

  private void updateField() {
    int index = fieldList.getSelectedIndex();
    if (filling || index < 0) {
      return;
    }
    fields.set(
        index, new Field(fieldLabel.getText(), ProScanForms.fromEditorText(answers.getText())));
    fieldList.repaint();
    updateForm();
  }

  private void updatePreview() {
    Form form = formList.getSelectedValue();
    preview.setText(form == null ? "" : ProScanForms.defaultText(form));
  }

  private void addForm() {
    String name =
        JOptionPane.showInputDialog(
            this, ProScanMessages.getString("forms.name"), ProScanMessages.getString("forms.add"));
    if (name == null || name.isBlank()) {
      return;
    }
    forms.addElement(new Form(name, "", List.of()));
    formList.setSelectedIndex(forms.size() - 1);
    changed = true;
  }

  private void removeForm() {
    int index = formList.getSelectedIndex();
    if (index >= 0
        && JOptionPane.showConfirmDialog(
                this,
                ProScanMessages.format("forms.remove.ask", forms.get(index).name()),
                getTitle(),
                JOptionPane.YES_NO_OPTION)
            == JOptionPane.YES_OPTION) {
      forms.remove(index);
      changed = true;
      if (!forms.isEmpty()) {
        formList.setSelectedIndex(Math.min(index, forms.size() - 1));
      }
      showForm();
    }
  }

  private void addField() {
    if (formList.getSelectedIndex() < 0) {
      return;
    }
    fields.addElement(new Field(ProScanMessages.getString("forms.item.new"), List.of()));
    fieldList.setSelectedIndex(fields.size() - 1);
    updateForm();
    fieldLabel.requestFocusInWindow();
    fieldLabel.selectAll();
  }

  private void removeField() {
    int index = fieldList.getSelectedIndex();
    if (index >= 0) {
      fields.remove(index);
      if (!fields.isEmpty()) {
        fieldList.setSelectedIndex(Math.min(index, fields.size() - 1));
      }
      updateForm();
      showField();
    }
  }

  private void moveField(int step) {
    int index = fieldList.getSelectedIndex();
    int to = index + step;
    if (index < 0 || to < 0 || to >= fields.size()) {
      return;
    }
    Field field = fields.remove(index);
    fields.add(to, field);
    fieldList.setSelectedIndex(to);
    updateForm();
  }

  private void saveAndClose() {
    try {
      ProScanForms.save(Collections.list(forms.elements()));
      changed = false;
      dispose();
    } catch (IOException e) {
      JOptionPane.showMessageDialog(
          this,
          ProScanMessages.format("status.error", e.getMessage()),
          getTitle(),
          JOptionPane.ERROR_MESSAGE);
    }
  }

  @Override
  public void dispose() {
    if (changed
        && isDisplayable()
        && JOptionPane.showConfirmDialog(
                this,
                ProScanMessages.getString("forms.unsaved"),
                getTitle(),
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) {
      return;
    }
    super.dispose();
  }

  private static final class OnEdit implements DocumentListener {
    private final Runnable action;

    OnEdit(Runnable action) {
      this.action = action;
    }

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
