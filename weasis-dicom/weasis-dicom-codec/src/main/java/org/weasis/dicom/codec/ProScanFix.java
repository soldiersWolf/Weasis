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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;
import org.dcm4che3.io.DicomOutputStream;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.opencv.data.ImageCV;
import org.weasis.opencv.data.PlanarImage;
import org.weasis.opencv.op.ImageTransformer;

/**
 * Vendor-specific workaround for the digital fluorograph ProScan-2000 (JSC Rentgenprom).
 *
 * <p>This deliberately departs from strict PS3.5 decoding to make these non-conformant files
 * readable. Their encoder (MIR CTN toolkit, Intel IPP JPEG 2000):
 *
 * <ul>
 *   <li>wraps the codestream in a truncated JP2 'jp2c' box, whereas PS3.5 A.4.4 requires a bare
 *       codestream starting with SOC (FF4F);
 *   <li>sets the MCT flag in COD for a single-component image (ISO 15444-1 A.6.1 forbids it);
 *   <li>leaves padding after the EOC marker;
 *   <li>skips the DC level shift, so decoded values are offset by 2^15;
 *   <li>stores the image upside down;
 *   <li>writes text in Windows-1251 without Specific Character Set (PS3.5 6.1.2.3).
 * </ul>
 *
 * The stream defects are repaired into a temporary copy at load time, the pixel defects when the
 * frame is decoded. The original file is never modified.
 */
public final class ProScanFix {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProScanFix.class);

  private static final Charset SOURCE_CHARSET = Charset.forName("windows-1251"); // NON-NLS
  private static final Set<VR> TEXT_VR = Set.of(VR.PN, VR.LO, VR.SH, VR.ST, VR.LT, VR.UT, VR.UC);
  // Backslash is not a value delimiter for these VRs (PS3.5 6.2)
  private static final Set<VR> SINGLE_VALUE_VR = Set.of(VR.ST, VR.LT, VR.UT);
  private static final int DC_OFFSET = 1 << 15;

  private ProScanFix() {}

  /** Whether the dataset was produced by a ProScan fluorograph. */
  public static boolean isProScan(Attributes dcm) {
    String manufacturer = dcm.getString(Tag.Manufacturer, "");
    String model = dcm.getString(Tag.ManufacturerModelName, "");
    return manufacturer.contains("Rentgenprom") && model.contains("ProScan"); // NON-NLS
  }

  /**
   * Writes a repaired copy of a ProScan file into the cache directory.
   *
   * @return the repaired copy, or empty when the file is not a ProScan image
   */
  public static Optional<Path> repairIfNeeded(Path file) {
    try {
      Attributes dataset;
      String tsuid;
      try (DicomInputStream in = new DicomInputStream(file.toFile())) {
        in.setIncludeBulkData(IncludeBulkData.YES);
        dataset = in.readDataset();
        tsuid = in.getTransferSyntax();
      }
      if (!isProScan(dataset)) {
        return Optional.empty();
      }
      repairCodestream(dataset);
      transcodeText(dataset);
      repairTags(dataset);

      Path out = Files.createTempFile(AppProperties.FILE_CACHE_DIR, "proscan_", ".dcm"); // NON-NLS
      try (DicomOutputStream dos = new DicomOutputStream(out.toFile())) {
        dos.writeDataset(dataset.createFileMetaInformation(tsuid), dataset);
        dos.finish();
      }
      LOGGER.info("ProScan file {} repaired into {}", file, out);
      return Optional.of(out);
    } catch (IOException | RuntimeException e) {
      LOGGER.error("Cannot repair the ProScan file {}", file, e);
      return Optional.empty();
    }
  }

  /**
   * Repairs in memory the header of an original ProScan file: text encoding and display
   * attributes, as in the repaired copy. Lets the images be listed without copying them.
   */
  public static void repairHeader(Attributes dataset) {
    try {
      transcodeText(dataset);
    } catch (IOException e) {
      LOGGER.error("Cannot transcode the ProScan text", e);
    }
    repairTags(dataset);
  }

  private static void repairCodestream(Attributes dataset) {
    if (dataset.getValue(Tag.PixelData) instanceof Fragments fragments) {
      for (int i = 1; i < fragments.size(); i++) {
        if (fragments.get(i) instanceof byte[] bytes) {
          fragments.set(i, cleanCodestream(bytes));
        }
      }
    }
  }

  /** Strips the JP2 box prefix and the trailing padding, and clears MCT for 1 or 2 components. */
  static byte[] cleanCodestream(byte[] fragment) {
    int start = indexOf(fragment, new byte[] {(byte) 0xFF, 0x4F, (byte) 0xFF, 0x51});
    if (start < 0) {
      return fragment;
    }
    int end = fragment.length;
    while (end - start > 2 && !isEndOfCodestream(fragment, end)) {
      end--;
    }
    byte[] cs = Arrays.copyOfRange(fragment, start, end);

    // SIZ: SOC(2) + marker(2) + Lsiz(2) + Rsiz(2) + 8 x uint32 -> Csiz at offset 40
    int components = ((cs[40] & 0xFF) << 8) | (cs[41] & 0xFF);
    int p = 2;
    while (p + 4 < cs.length) {
      int marker = ((cs[p] & 0xFF) << 8) | (cs[p + 1] & 0xFF);
      int length = ((cs[p + 2] & 0xFF) << 8) | (cs[p + 3] & 0xFF);
      if (marker == 0xFF90) { // SOT: end of the main header
        break;
      }
      if (marker == 0xFF52 && components < 3) { // COD: Scod, progression, layers(2), MCT
        cs[p + 8] = 0;
      }
      p += 2 + length;
    }
    if (cs.length % 2 != 0) {
      cs = Arrays.copyOf(cs, cs.length + 1);
    }
    return cs;
  }

  private static boolean isEndOfCodestream(byte[] data, int end) {
    return data[end - 2] == (byte) 0xFF && data[end - 1] == (byte) 0xD9;
  }

  private static int indexOf(byte[] data, byte[] pattern) {
    outer:
    for (int i = 0; i <= data.length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static void transcodeText(Attributes dataset) throws IOException {
    if (dataset.containsValue(Tag.SpecificCharacterSet)) {
      return;
    }
    for (int tag : dataset.tags()) {
      VR vr = dataset.getVR(tag);
      if (TEXT_VR.contains(vr) && dataset.getValue(tag) instanceof byte[] raw) {
        String text = new String(raw, SOURCE_CHARSET).replace("\0", "").trim();
        String[] values = SINGLE_VALUE_VR.contains(vr) ? new String[] {text} : text.split("\\\\");
        dataset.setString(tag, vr, values);
      }
    }
    dataset.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
  }

  private static void repairTags(Attributes dataset) {
    String[] centers = dataset.getStrings(Tag.WindowCenter);
    String[] widths = dataset.getStrings(Tag.WindowWidth);
    // Full range of the shifted 15-bit values first, like the default view of the vendor viewer
    String[] explanations = dataset.getStrings(Tag.WindowCenterWidthExplanation);
    boolean done = explanations != null && List.of(explanations).contains("PROSCAN"); // NON-NLS
    if (!done && centers != null && widths != null && centers.length > 0 && widths.length > 0) {
      dataset.setString(Tag.WindowCenter, VR.DS, "16384", centers[0]);
      dataset.setString(Tag.WindowWidth, VR.DS, "32768", widths[0]);
      dataset.setString(Tag.WindowCenterWidthExplanation, VR.LO, "FULL", "PROSCAN"); // NON-NLS
    }
    dataset.setString(Tag.PatientOrientation, VR.CS, "L", "F");
    if (!dataset.containsValue(Tag.PatientID) || dataset.getString(Tag.PatientID, "").isBlank()) {
      String name = dataset.getString(Tag.PatientName, "");
      String key = name + "|" + dataset.getString(Tag.PatientBirthDate, "");
      dataset.setString(Tag.PatientID, VR.LO, "PS" + shortHash(key)); // NON-NLS
    }
  }

  private static String shortHash(String value) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-1") // NON-NLS
              .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().withUpperCase().formatHex(digest, 0, 5);
    } catch (java.security.NoSuchAlgorithmException e) {
      return Integer.toHexString(value.hashCode()).toUpperCase();
    }
  }

  /** Removes the missing DC level shift and puts the image upright. */
  public static PlanarImage repairPixels(PlanarImage img) {
    Mat src = img.toMat();
    Mat low = new Mat();
    Core.inRange(src, new Scalar(0), new Scalar(DC_OFFSET - 1.0), low);
    boolean shifted = Core.countNonZero(low) * 1000L < src.total();
    low.release();

    ImageCV fixed = new ImageCV();
    if (shifted) {
      Core.subtract(src, new Scalar(DC_OFFSET), fixed); // saturates rounding noise at 0
    } else {
      src.copyTo(fixed);
    }
    PlanarImage result = ImageTransformer.flip(fixed, 0); // 0 = around the x-axis
    if (result != fixed) {
      fixed.release();
    }
    return result;
  }
}
