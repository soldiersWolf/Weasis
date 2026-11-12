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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerDownloadViewTest {

  @Test
  void aNameIsSearchedAsAPrefixWithCaretsBetweenTheParts() {
    assertEquals("Ivanenko*", ServerDownloadView.wildcard("Ivanenko"));
    assertEquals("Ivanenko^Ivan*", ServerDownloadView.wildcard("  Ivanenko   Ivan "));
    assertEquals("Iva?enko", ServerDownloadView.wildcard("Iva?enko"));
  }

  @Test
  void aBirthDateIsReadAsTheUserWritesIt() {
    assertEquals("19730315", ServerDownloadView.dicomDate("15.03.1973"));
    assertEquals("19730315", ServerDownloadView.dicomDate("15/03/1973"));
    assertEquals("19730315", ServerDownloadView.dicomDate("19730315"));
    assertEquals("19730101-19731231", ServerDownloadView.dicomDate("1973"));
  }
}
