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

import java.util.Hashtable;
import org.weasis.dicom.explorer.imp.DicomImportFactory;
import org.weasis.dicom.explorer.imp.ImportDicom;

/** Adds the sources page, the watched folders and the synchronized archives, to the import. */
@org.osgi.service.component.annotations.Component(service = DicomImportFactory.class)
public class SourcesFactory implements DicomImportFactory {

  @Override
  public ImportDicom createDicomImportPage(Hashtable<String, Object> properties) {
    return new SourcesView();
  }
}
