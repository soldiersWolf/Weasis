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

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Strings of the ProScan report. They live in their own bundle, with their translations next to it,
 * because these keys are not part of the shared translation artifact (weasis-i18n-dist).
 */
final class ProScanMessages {
  private static final String BUNDLE_NAME =
      "org.weasis.dicom.explorer.proscan.proscan"; // NON-NLS

  private ProScanMessages() {}

  static String getString(String key) {
    try {
      return ResourceBundle.getBundle(BUNDLE_NAME).getString(key);
    } catch (MissingResourceException e) {
      return '!' + key + '!';
    }
  }

  static String format(String key, Object... args) {
    return MessageFormat.format(getString(key), args);
  }
}
