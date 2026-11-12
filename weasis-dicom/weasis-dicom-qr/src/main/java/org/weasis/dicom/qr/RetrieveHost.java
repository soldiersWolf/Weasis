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

import java.awt.Component;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode.RetrieveType;
import org.weasis.dicom.explorer.pref.node.DefaultDicomNode;
import org.weasis.dicom.tool.DicomListener;

/**
 * What a retrieve needs from where it was started: the query dialog, or the background archive
 * synchronization that has no dialog.
 */
public interface RetrieveHost {

  /** Model holding the studies found by the query, with the series once they were listed. */
  DicomModel getQueryModel();

  /** Calling node of the DIMSE retrieve, null when none is configured. */
  DefaultDicomNode getCallingNode();

  RetrieveType getRetrieveType();

  /** Listener receiving C-MOVE responses, null when it could not be started. */
  DicomListener getDicomListener();

  /** Parent of the messages shown to the user, null for none. */
  Component getDialogParent();
}
