/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.launcher;

import com.formdev.flatlaf.FlatLightLaf;

/**
 * Light theme with a pale blue window chrome, familiar to users of classic Windows DICOM viewers.
 * Its colors are in {@code org/weasis/theme/FlatWeasisLightTheme.properties}.
 */
public class FlatWeasisLightTheme extends FlatLightLaf {
  public static final String NAME = "Weasis Light Blue"; // NON-NLS

  public static boolean setup() {
    return setup(new FlatWeasisLightTheme());
  }

  @Override
  public String getName() {
    return NAME;
  }
}
