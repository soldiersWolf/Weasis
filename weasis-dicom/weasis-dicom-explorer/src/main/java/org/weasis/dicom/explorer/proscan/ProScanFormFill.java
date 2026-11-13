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

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;
import org.weasis.dicom.explorer.proscan.ProScanForms.Field;
import org.weasis.dicom.explorer.proscan.ProScanForms.Form;

/** Picks an answer for each item of a form and shows the resulting description. */
final class ProScanFormFill extends JDialog {

  /** The text made with the form, and whether it replaces the current description. */
  record Result(String text, boolean replace) {}

  private final List<JComboBox<String>> choices = new ArrayList<>();
  private final JTextArea preview = new JTextArea(4, 40);
  private Result result;

  private ProScanFormFill(Component parent, Form form, boolean hasText) {
    super(SwingUtilities.getWindowAncestor(parent), form.name(), ModalityType.APPLICATION_MODAL);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    JPanel panel = new JPanel(new MigLayout("fillx, ins 12, gap 8 6", "[][grow,fill]", ""));
    for (Field field : form.fields()) {
      List<String> answers = new ArrayList<>(field.answers());
      if (!answers.contains("")) {
        answers.add("");
      }
      JComboBox<String> combo = new JComboBox<>(answers.toArray(String[]::new));
      combo.setRenderer(
          (list, value, index, selected, focus) -> {
            JLabel label =
                new JLabel(value == null || value.isEmpty() ? ProScanForms.NOTHING : value);
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
          });
      combo.setSelectedIndex(0);
      combo.addActionListener(_ -> updatePreview());
      choices.add(combo);
      panel.add(new JLabel(field.label()));
      panel.add(combo, "wmin 0, wrap"); // NON-NLS
    }
    preview.setLineWrap(true);
    preview.setWrapStyleWord(true);
    preview.setEditable(false);
    panel.add(new JLabel(ProScanMessages.getString("forms.preview")), "span, gaptop 8, wrap");
    panel.add(new JScrollPane(preview), "span, growx, wrap"); // NON-NLS

    JButton replace = new JButton(ProScanMessages.getString("forms.replace"));
    JButton append = new JButton(ProScanMessages.getString("forms.append"));
    JButton cancel = new JButton(ProScanMessages.getString("export.close"));
    replace.addActionListener(_ -> finish(true));
    append.addActionListener(_ -> finish(false));
    cancel.addActionListener(_ -> dispose());
    append.setEnabled(hasText);
    panel.add(replace, "span, split 3, right, gaptop 8"); // NON-NLS
    panel.add(append);
    panel.add(cancel);
    getRootPane().setDefaultButton(replace);

    setContentPane(panel);
    updatePreview();
    pack();
    setLocationRelativeTo(parent);
  }

  /** Shows the form; empty when the user closes it without inserting the text. */
  static Optional<Result> fill(Component parent, Form form, boolean hasText) {
    ProScanFormFill dialog = new ProScanFormFill(parent, form, hasText);
    dialog.setVisible(true);
    return Optional.ofNullable(dialog.result);
  }

  private String text() {
    return ProScanForms.compose(
        choices.stream().map(c -> (String) c.getSelectedItem()).toList());
  }

  private void updatePreview() {
    preview.setText(text());
  }

  private void finish(boolean replace) {
    result = new Result(text(), replace);
    dispose();
  }
}
