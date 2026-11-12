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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.weasis.opencv.data.ImageCV;
import org.weasis.opencv.data.PlanarImage;

class ProScanPixelsTest {

  @BeforeAll
  static void loadOpenCV() throws IOException {
    Path modules = Path.of("..", "..", "weasis-opencv").toAbsolutePath().normalize();
    boolean loaded = false;
    if (Files.isDirectory(modules)) {
      try (var files = Files.walk(modules, 4)) {
        for (Path library :
            files
                .filter(
                    p -> p.getFileName().toString().equals(System.mapLibraryName("opencv_java")))
                .toList()) {
          try {
            System.load(library.toString());
            loaded = true;
            break;
          } catch (UnsatisfiedLinkError ignored) {
            // Try the next platform's build output.
          }
        }
      }
    }
    assumeTrue(loaded, "OpenCV native library unavailable");
  }

  private static ImageCV image(short[] rowMajor) {
    ImageCV img = new ImageCV(2, 2, CvType.CV_16UC1);
    img.put(0, 0, rowMajor);
    return img;
  }

  private static short[] values(PlanarImage img) {
    Mat mat = img.toMat();
    short[] out = new short[(int) mat.total()];
    mat.get(0, 0, out);
    return out;
  }

  @Test
  void removesTheMissingDcShiftAndPutsTheImageUpright() {
    // Encoder output: every value carries the 2^15 offset; the bottom row is the top of the body
    ImageCV img = image(new short[] {(short) 40000, (short) 50000, (short) 32768, (short) 65535});

    short[] fixed = values(ProScanFix.repairPixels(img));

    assertArrayEquals(new short[] {0, 32767, 7232, 17232}, fixed);
  }

  @Test
  void onlyFlipsWhenValuesAreNotOffset() {
    ImageCV img = image(new short[] {100, 200, 300, 400});

    short[] fixed = values(ProScanFix.repairPixels(img));

    assertArrayEquals(new short[] {300, 400, 100, 200}, fixed);
  }
}
