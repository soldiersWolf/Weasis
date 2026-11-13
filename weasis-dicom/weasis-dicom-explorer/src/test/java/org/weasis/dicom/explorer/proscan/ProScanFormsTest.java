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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.proscan.ProScanForms.Field;
import org.weasis.dicom.explorer.proscan.ProScanForms.Form;

class ProScanFormsTest {

  @TempDir Path dir;

  private static final Form SCREENING =
      new Form(
          "Профогляд",
          "Без патологічних змін.",
          List.of(
              new Field("Легені", List.of("Легені без змін", "Фіброзні зміни")),
              new Field("Хребет", List.of("", "Сколіоз"))));

  @Test
  void keepsTheFormsWithTheirCyrillicText() throws IOException {
    Path file = dir.resolve(ProScanForms.FILE_NAME);
    Form empty = new Form("Порожня", "", List.of());

    ProScanForms.write(file, List.of(SCREENING, empty));

    assertEquals(List.of(SCREENING, empty), ProScanForms.read(file));
  }

  @Test
  void putsThePickedAnswersEndToEnd() {
    assertEquals(
        "Легені без змін. Сколіоз! Серце в нормі.",
        ProScanForms.compose(
            Arrays.asList("Легені без змін", "", null, "Сколіоз!", " Серце в нормі ")));
    assertEquals("Легені без змін.", ProScanForms.defaultText(SCREENING));
  }

  @Test
  void tellsWhetherAFormLeavesSomethingToPick() {
    assertTrue(ProScanForms.hasChoices(SCREENING));
    assertFalse(
        ProScanForms.hasChoices(new Form("Норма", "", List.of(new Field("Легені", List.of("x"))))));
  }

  @Test
  void findsTheConclusionOfTheFormInTheDictionary() {
    List<String> dictionary = List.of("Аортосклероз.", "без  патологічних змін");

    assertEquals(
        Optional.of("без  патологічних змін"), ProScanForms.matchConclusion(SCREENING, dictionary));
    assertEquals(
        Optional.empty(),
        ProScanForms.matchConclusion(new Form("x", "", List.of()), dictionary));
  }

  @Test
  void typesOneAnswerPerLineWithADashForNothing() {
    assertEquals(
        List.of("Легені без змін", "", ""),
        ProScanForms.fromEditorText("Легені без змін\n\n—\n -\n"));
    assertEquals("Сколіоз\n—", ProScanForms.toEditorText(List.of("Сколіоз", "")));
  }
}
