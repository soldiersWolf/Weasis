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

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.explorer.proscan.ProScanPrint.Sheet;

class ProScanPrintTest {

  private static final Sheet SHEET = new Sheet("Тестовий Т.", null, null, null, "", "", "");

  @Test
  void wrapsTheTextToTheWidthAndKeepsItsLineBreaks() {
    // One unit per character
    assertEquals(
        List.of("Легені без", "змін.", "Серце в", "нормі."),
        ProScanPrint.wrap("Легені без змін.\nСерце в нормі.", 10, String::length));
  }

  @Test
  void keepsAWordLongerThanTheWidthOnItsOwnLine() {
    assertEquals(
        List.of("a", "Аортосклероз", "b"),
        ProScanPrint.wrap("a Аортосклероз b", 5, String::length));
  }

  @Test
  void printsOneOrTwoFormsPerSheet() {
    List<Sheet> three = Collections.nCopies(3, SHEET);

    assertEquals(3, new ProScanPrint(three, 1, "").pageCount());
    assertEquals(2, new ProScanPrint(three, 2, "").pageCount());
  }
}
