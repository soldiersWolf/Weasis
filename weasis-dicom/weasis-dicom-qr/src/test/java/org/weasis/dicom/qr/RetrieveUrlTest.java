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

class RetrieveUrlTest {

  private static final String ROOT = "http://pacs.example:8042/dicom-web"; // NON-NLS
  private static final String SERIES = ROOT + "/studies/1.2.3/series/1.2.3.4"; // NON-NLS

  @Test
  void dropsTheTrailingSlashesOfARoot() {
    assertEquals(ROOT, RetrieveTask.withoutTrailingSlash(ROOT + "//"));
    assertEquals(ROOT, RetrieveTask.withoutTrailingSlash(ROOT));
  }

  @Test
  void buildsTheSeriesUrlFromTheRootWhenTheServerGivesNone() {
    assertEquals(SERIES, RetrieveTask.seriesRetrieveUrl(null, ROOT, "1.2.3", "1.2.3.4"));
  }

  @Test
  void keepsTheServerUrlUnderTheConfiguredRoot() {
    assertEquals(SERIES, RetrieveTask.seriesRetrieveUrl(SERIES, ROOT, "1.2.3", "1.2.3.4"));
  }

  @Test
  void replacesAServerUrlPointingToAnotherAddress() {
    String internal = "http://10.0.0.5:8080/dicom-web/studies/1.2.3/series/1.2.3.4"; // NON-NLS
    assertEquals(SERIES, RetrieveTask.seriesRetrieveUrl(internal, ROOT, "1.2.3", "1.2.3.4"));
  }
}
