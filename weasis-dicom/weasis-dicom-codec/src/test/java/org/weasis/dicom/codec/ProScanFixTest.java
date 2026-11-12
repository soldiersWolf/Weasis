/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class ProScanFixTest {

  /** Synthetic single-component codestream shaped like the ProScan-2000 output. */
  private static byte[] proScanFragment() {
    var out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {0, 0, 0, 0, 'j', 'p', '2', 'c'}); // truncated JP2 box
    out.writeBytes(new byte[] {(byte) 0xFF, 0x4F}); // SOC
    out.writeBytes(new byte[] {(byte) 0xFF, 0x51, 0, 41, 0, 0}); // SIZ, Lsiz, Rsiz
    out.writeBytes(new byte[32]); // image and tile geometry
    out.writeBytes(new byte[] {0, 1, 0x0F, 1, 1}); // Csiz = 1, Ssiz, XRsiz, YRsiz
    out.writeBytes(new byte[] {(byte) 0xFF, 0x52, 0, 12, 0, 0, 0, 1, 1, 11, 4, 4, 0, 1}); // MCT = 1
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0x90, 0, 10, 0, 0, 0, 0, 0, 0, 0, 1}); // SOT
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0x93, 1, 2, 3}); // SOD and data
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9, 0}); // EOC and padding
    return out.toByteArray();
  }

  @Test
  void cleanCodestreamRemovesBoxPaddingAndMct() {
    byte[] cs = ProScanFix.cleanCodestream(proScanFragment());

    assertEquals((byte) 0xFF, cs[0]);
    assertEquals(0x4F, cs[1]);
    assertEquals(0, cs[45 + 8], "MCT must be cleared for a single component");
    int end = cs[cs.length - 1] == 0 ? cs.length - 3 : cs.length - 2;
    assertEquals((byte) 0xFF, cs[end]);
    assertEquals((byte) 0xD9, cs[end + 1]);
    assertEquals(0, cs.length % 2);
  }

  @Test
  void cleanCodestreamKeepsUnknownData() {
    byte[] data = {1, 2, 3, 4};
    assertArrayEquals(data, ProScanFix.cleanCodestream(data));
  }

  @Test
  void detectsProScanOnly() {
    var dcm = new Attributes();
    dcm.setString(Tag.Manufacturer, VR.LO, "JSC Rentgenprom");
    dcm.setString(Tag.ManufacturerModelName, VR.LO, "ProScan-2000");
    assertTrue(ProScanFix.isProScan(dcm));

    dcm.setString(Tag.Manufacturer, VR.LO, "Other vendor");
    assertFalse(ProScanFix.isProScan(dcm));
  }
}
