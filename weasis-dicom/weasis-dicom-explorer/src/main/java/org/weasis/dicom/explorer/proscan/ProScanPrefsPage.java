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

import net.miginfocom.swing.MigLayout;
import org.weasis.core.api.gui.util.AbstractItemDialogPage;

/** Page of the preferences for ProScan, right after the general page, easy to find. */
public class ProScanPrefsPage extends AbstractItemDialogPage {

  public ProScanPrefsPage() {
    super(ProScanMessages.getString("settings.title"), -9);
    setComponentPosition(-9);
    setLayout(new MigLayout("fillx, ins 10", "[grow,fill]", "")); // NON-NLS
    add(new ProScanSettingsPanel(), "growx"); // NON-NLS
  }

  @Override
  public void closeAdditionalWindow() {
    // Every change is saved at once
  }

  @Override
  public void resetToDefaultValues() {
    // Nothing to reset: the database and the facility are what the user chose
  }
}
