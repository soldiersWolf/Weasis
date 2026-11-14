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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JetRunnerTest {

  @Test
  void writesARequestOnOneAsciiLine() {
    Map<String, String> args = new LinkedHashMap<>();
    args.put("PROSCAN_MODE", "write");
    args.put("PROSCAN_TEXT", " Легені\r\nбез змін");

    String line = JetRunner.requestLine(args);

    String text =
        Base64.getEncoder().encodeToString(" Легені\r\nбез змін".getBytes(StandardCharsets.UTF_8));
    assertEquals("PROSCAN_MODE=d3JpdGU= PROSCAN_TEXT=" + text, line);
  }

  @Test
  void givesALongPeriodMoreTime() {
    assertEquals(180, JetRunner.timeoutSeconds("browse"));
    assertEquals(20, JetRunner.timeoutSeconds("write"));
  }
}
