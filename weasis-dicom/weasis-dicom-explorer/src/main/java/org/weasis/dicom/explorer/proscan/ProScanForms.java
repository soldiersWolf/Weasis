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
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.dicom.explorer.FolderWatcher;

/**
 * Report forms made by the doctors: each form has items, such as lungs or heart, and each item a
 * few answers to pick from, the first one by default. The description is the picked answers put
 * end to end, and a form may also set the conclusion.
 *
 * <p>The forms are kept next to the shared ProScan database, so that every computer of the
 * hospital uses the same ones, or in the Weasis folder of this computer when there is no database.
 */
public final class ProScanForms {

  static final String FILE_NAME = "weasis-forms.properties"; // NON-NLS

  /** How the answer writing nothing is shown and typed among the answers of an item. */
  static final String NOTHING = "—";

  /** An item of a form and its answers; an empty answer leaves the item out of the text. */
  public record Field(String label, List<String> answers) {
    public Field {
      label = label == null ? "" : label.strip();
      answers = answers.stream().map(String::strip).toList();
    }
  }

  /** A form: its name, the conclusion it sets (empty to keep the current one) and its items. */
  public record Form(String name, String conclusion, List<Field> fields) {
    public Form {
      name = name == null ? "" : name.strip();
      conclusion = conclusion == null ? "" : conclusion.strip();
      fields = List.copyOf(fields);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private ProScanForms() {}

  /** Where the forms are kept. */
  public static Path location() {
    return ProScanDatabase.sharedDatabase(FolderWatcher.getWatchedFolders())
        .map(Path::getParent)
        .filter(Files::isWritable)
        .orElse(AppProperties.WEASIS_PATH)
        .resolve(FILE_NAME);
  }

  /** The saved forms, or examples when none was saved yet. */
  public static List<Form> load() {
    Path file = location();
    if (!Files.isRegularFile(file)) {
      return examples();
    }
    try {
      return read(file);
    } catch (IOException e) {
      return examples();
    }
  }

  static List<Form> read(Path file) throws IOException {
    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(reader);
    }
    List<Form> forms = new ArrayList<>();
    for (int f = 0; props.containsKey("form." + f + ".name"); f++) { // NON-NLS
      String prefix = "form." + f + "."; // NON-NLS
      List<Field> fields = new ArrayList<>();
      for (int i = 0; props.containsKey(prefix + "item." + i + ".label"); i++) { // NON-NLS
        String item = prefix + "item." + i + "."; // NON-NLS
        String answers = props.getProperty(item + "answers", ""); // NON-NLS
        fields.add(
            new Field(
                props.getProperty(item + "label"), // NON-NLS
                Arrays.asList(answers.split("\n", -1))));
      }
      forms.add(
          new Form(
              props.getProperty(prefix + "name"), // NON-NLS
              props.getProperty(prefix + "conclusion", ""), // NON-NLS
              fields));
    }
    return forms;
  }

  /** Saves the forms for every computer using the same database. */
  public static void save(List<Form> forms) throws IOException {
    write(location(), forms);
  }

  static void write(Path file, List<Form> forms) throws IOException {
    Properties props = new Properties();
    for (int f = 0; f < forms.size(); f++) {
      Form form = forms.get(f);
      String prefix = "form." + f + "."; // NON-NLS
      props.setProperty(prefix + "name", form.name()); // NON-NLS
      props.setProperty(prefix + "conclusion", form.conclusion()); // NON-NLS
      for (int i = 0; i < form.fields().size(); i++) {
        Field field = form.fields().get(i);
        String item = prefix + "item." + i + "."; // NON-NLS
        props.setProperty(item + "label", field.label()); // NON-NLS
        props.setProperty(item + "answers", String.join("\n", field.answers())); // NON-NLS
      }
    }
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path partial = file.resolveSibling(file.getFileName() + ".part"); // NON-NLS
    // A writer keeps the Cyrillic letters readable in the file
    try (Writer writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
      props.store(writer, "Weasis report forms"); // NON-NLS
    }
    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * The description made of the picked answers: each one ends with a period, and the answers are
   * put end to end like the descriptions ProScan already holds.
   */
  public static String compose(List<String> picked) {
    StringBuilder text = new StringBuilder();
    for (String answer : picked) {
      String a = answer == null ? "" : answer.strip();
      if (a.isEmpty()) {
        continue;
      }
      if (!text.isEmpty()) {
        text.append(' ');
      }
      text.append(a);
      if (!a.endsWith(".") && !a.endsWith("!") && !a.endsWith("?")) {
        text.append('.');
      }
    }
    return text.toString();
  }

  static String toEditorText(List<String> answers) {
    return String.join("\n", answers.stream().map(a -> a.isEmpty() ? NOTHING : a).toList());
  }

  /** Answers typed one per line: blank lines are dropped, "—" or "-" writes nothing. */
  static List<String> fromEditorText(String text) {
    List<String> list = new ArrayList<>();
    for (String line : text.split("\\R")) {
      String a = line.strip();
      if (a.isEmpty()) {
        continue;
      }
      list.add(NOTHING.equals(a) || "-".equals(a) ? "" : a);
    }
    return list;
  }

  /** The text of a form with the first answer of every item. */
  public static String defaultText(Form form) {
    return compose(
        form.fields().stream()
            .map(f -> f.answers().isEmpty() ? "" : f.answers().getFirst())
            .toList());
  }

  /** Whether the form leaves something to pick, i.e. one of its items has several answers. */
  public static boolean hasChoices(Form form) {
    return form.fields().stream().anyMatch(f -> f.answers().size() > 1);
  }

  /** Conclusion of the dictionary matching the one of the form, ignoring case and spaces. */
  public static <T> Optional<T> matchConclusion(Form form, List<T> entries) {
    String wanted = normalize(form.conclusion());
    if (wanted.isEmpty()) {
      return Optional.empty();
    }
    return entries.stream().filter(e -> normalize(String.valueOf(e)).equals(wanted)).findFirst();
  }

  private static String normalize(String text) {
    String single = text.strip().replaceAll("\\s+", " "); // NON-NLS
    return single.replaceAll("\\.$", "").toLowerCase(Locale.ROOT); // NON-NLS
  }

  /**
   * Examples to start from, meant to be edited. Their phrases are data in the language of the
   * ProScan database, taken from its descriptions, not text of the user interface.
   */
  static List<Form> examples() {
    return List.of(
        new Form(
            ProScanMessages.getString("forms.example.normal"),
            "Без патологічних змін.", // NON-NLS
            List.of(
                new Field(
                    "Легені", // NON-NLS
                    List.of("Легені без свіжих вогнищевих та інфільтративних змін")), // NON-NLS
                new Field("Серце", List.of("Серце без особливостей")))), // NON-NLS
        new Form(
            ProScanMessages.getString("forms.example.screening"),
            "",
            List.of(
                new Field(
                    "Легені", // NON-NLS
                    List.of(
                        "Легені без свіжих вогнищевих та інфільтративних змін", // NON-NLS
                        "Посилення легеневого малюнка в нижніх відділах", // NON-NLS
                        "Фіброзні зміни")), // NON-NLS
                new Field(
                    "Корені", // NON-NLS
                    List.of("", "Корені ущільнені")), // NON-NLS
                new Field(
                    "Діафрагма", // NON-NLS
                    List.of("", "Високе стояння правого купола діафрагми")), // NON-NLS
                new Field(
                    "Серце", // NON-NLS
                    List.of(
                        "Серце без особливостей", // NON-NLS
                        "Серце: межі помірно розширені вліво", // NON-NLS
                        "Аортосклероз")), // NON-NLS
                new Field("Хребет", List.of("", "Сколіоз"))))); // NON-NLS
  }
}
