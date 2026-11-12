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

import java.io.IOException;
import java.net.HttpURLConnection;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.QueryRetrieveLevel;
import org.weasis.core.api.net.HttpStream;
import org.weasis.core.api.net.HttpUtils;
import org.weasis.core.api.net.auth.AuthMethod;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode;
import org.weasis.dicom.explorer.pref.node.DefaultDicomNode;
import org.weasis.dicom.explorer.pref.node.DicomWebNode;
import org.weasis.dicom.explorer.rs.RsQueryResult;
import org.weasis.dicom.op.CFind;
import org.weasis.dicom.param.DicomParam;
import org.weasis.dicom.param.DicomState;

/**
 * Tells whether an archive answers queries: a C-FIND for a study that cannot exist, or a QIDO-RS
 * request for one study. Unlike an echo, it also proves that this computer may query the archive.
 */
final class ConnectionCheck {

  /** Outcome of a check; {@code hint} suggests a fix, such as a missing /dicom-web path. */
  record Result(boolean ok, String message, String hint, long millis) {}

  private static final String NO_SUCH_STUDY = "1.2.826.0.1.3680043.10.1.0.0.0"; // NON-NLS

  private ConnectionCheck() {}

  static Result check(AbstractDicomNode archive, DefaultDicomNode calling, AuthMethod auth) {
    long start = System.currentTimeMillis();
    try {
      if (archive instanceof DefaultDicomNode node) {
        return checkDimse(node, calling, start);
      }
      if (archive instanceof DicomWebNode node) {
        return checkWeb(node, auth, start);
      }
      return new Result(false, "?", null, 0);
    } catch (Exception e) {
      return new Result(false, String.valueOf(e.getMessage()), null, elapsed(start));
    }
  }

  private static Result checkDimse(DefaultDicomNode node, DefaultDicomNode calling, long start) {
    if (calling == null) {
      return new Result(false, Messages.getString("RetrieveTask.no_calling_node"), null, 0);
    }
    DicomState state =
        CFind.process(
            DicomQrView.buildFindParams(false),
            calling.getDicomNodeWithOnlyAET(),
            node.getDicomNode(),
            1,
            QueryRetrieveLevel.STUDY,
            new DicomParam(Tag.StudyInstanceUID, NO_SUCH_STUDY));
    boolean ok = state.getStatus() == Status.Success || state.getStatus() == Status.Pending;
    return new Result(ok, ok ? null : String.valueOf(state.getMessage()), null, elapsed(start));
  }

  private static Result checkWeb(DicomWebNode node, AuthMethod auth, long start)
      throws Exception {
    String base = RetrieveTask.dicomWebRoot(node);
    try {
      queryOneStudy(node, base, auth);
      return new Result(true, null, null, elapsed(start));
    } catch (Exception e) {
      // A frequent mistake is the server root instead of its DICOMweb root, e.g. Orthanc
      if (!base.endsWith("/dicom-web")) { // NON-NLS
        try {
          queryOneStudy(node, base + "/dicom-web", auth); // NON-NLS
          return new Result(false, e.getMessage(), base + "/dicom-web", elapsed(start)); // NON-NLS
        } catch (Exception ignored) {
          // Not the reason: report the first error
        }
      }
      throw e;
    }
  }

  private static void queryOneStudy(DicomWebNode node, String base, AuthMethod auth)
      throws Exception {
    // Read the status: a wrong address answers 404, which a query treats as no match
    try (HttpStream response =
        HttpUtils.getHttpResponse(
            base + "/studies?limit=1", // NON-NLS
            RsQueryResult.jsonQueryParameters(node.getHeaders()),
            auth)) {
      int code = response.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK
          && code != HttpURLConnection.HTTP_PARTIAL
          && code != HttpURLConnection.HTTP_NO_CONTENT) {
        throw new IOException("HTTP " + code); // NON-NLS
      }
    }
  }

  private static long elapsed(long start) {
    return System.currentTimeMillis() - start;
  }
}
