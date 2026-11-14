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

import com.formdev.flatlaf.util.SystemInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.BusyException;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.UnavailableException;

/**
 * Opens the ProScan database through the Jet engine shipped with Windows. ProScan is a Jet 3
 * (Access 97) application: going through Jet, rather than writing the file directly, keeps the
 * record locks it shares with ProScan. Jet exists only in 32 bits, so the request runs in the
 * 32-bit PowerShell of Windows, and nothing has to be installed.
 */
final class JetRunner implements ProScanDatabase.Runner {

  private static final String SCRIPT = "jet.ps1"; // NON-NLS
  private static final long REACH_SECONDS = 5;
  private static final ExecutorService REACH_EXECUTOR =
      Executors.newCachedThreadPool(Thread.ofVirtual().name("ProScan reach").factory()); // NON-NLS

  private Path script;

  /** The 32-bit PowerShell, absent outside Windows. */
  static Optional<Path> executable() {
    String root = System.getenv("SystemRoot"); // NON-NLS
    if (!SystemInfo.isWindows || root == null) {
      return Optional.empty();
    }
    // On 64-bit Windows the 32-bit system lives in SysWOW64, System32 holds 64-bit programs
    Path wow = Path.of(root, "SysWOW64"); // NON-NLS
    Path system = Files.isDirectory(wow) ? wow : Path.of(root, "System32"); // NON-NLS
    Path exe = system.resolve(Path.of("WindowsPowerShell", "v1.0", "powershell.exe")); // NON-NLS
    return Files.isRegularFile(exe) ? Optional.of(exe) : Optional.empty();
  }

  @Override
  public List<List<String>> run(Map<String, String> args) throws IOException {
    Path exe =
        executable().orElseThrow(() -> new IOException(ProScanMessages.getString("error.os")));
    Path db = Path.of(args.get("PROSCAN_DB"));
    boolean write = "write".equals(args.get("PROSCAN_MODE")); // NON-NLS
    checkReachable(db);
    ProcessBuilder builder =
        new ProcessBuilder(
            exe.toString(),
            "-NoLogo", // NON-NLS
            "-NoProfile", // NON-NLS
            "-NonInteractive", // NON-NLS
            "-ExecutionPolicy", // NON-NLS
            "Bypass", // NON-NLS
            "-File", // NON-NLS
            script().toString());
    builder.redirectErrorStream(true);
    // Written to a file, so that a request hanging on the network can be given up
    Path output = Files.createTempFile(AppProperties.FILE_CACHE_DIR, "proscan-", ".out"); // NON-NLS
    builder.redirectOutput(output.toFile());
    // Base64 keeps any character of a description out of reach of the console code page
    args.forEach(
        (key, value) ->
            builder
                .environment()
                .put(
                    key,
                    Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
    try {
      Process process = builder.start();
      try {
        if (!process.waitFor(timeoutSeconds(args.get("PROSCAN_MODE")), TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new UnavailableException(
              ProScanMessages.format("error.timeout", args.get("PROSCAN_DB")));
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      List<String> lines;
      try (InputStream in = Files.newInputStream(output)) {
        lines = readLines(in);
      }
      List<List<String>> rows;
      try {
        rows = ProScanDatabase.parse(lines);
      } catch (BusyException e) {
        throw e;
      } catch (IOException e) {
        // Jet writes a lock file next to the database: a read-only folder refuses any change
        if (write && db.getParent() != null && !Files.isWritable(db.getParent())) {
          throw new IOException(ProScanMessages.format("error.readonly", db.getParent()), e);
        }
        throw e;
      }
      if (process.exitValue() != 0) {
        // PowerShell failed before the script could report it, e.g. blocked by a policy
        throw new IOException(String.join(" ", lines).strip());
      }
      return rows;
    } finally {
      Files.deleteIfExists(output);
    }
  }

  /** Time given to a request: browsing or copying a long period reads many records. */
  static long timeoutSeconds(String mode) {
    return "browse".equals(mode) ? 180 : 20; // NON-NLS
  }

  /**
   * Tells at once when the database cannot be reached: Jet itself would wait for the network for
   * minutes. A computer that is off is noticed within a few seconds.
   */
  static void checkReachable(Path db) throws IOException {
    CompletableFuture<Boolean> look =
        CompletableFuture.supplyAsync(() -> Files.isRegularFile(db), REACH_EXECUTOR);
    boolean exists;
    try {
      exists = look.get(REACH_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      look.cancel(true);
      throw new UnavailableException(ProScanMessages.format("error.unreachable", db));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    } catch (ExecutionException e) {
      throw new UnavailableException(ProScanMessages.format("error.unreachable", db));
    }
    if (!exists) {
      throw new UnavailableException(ProScanMessages.format("error.nofile", db));
    }
  }

  private static List<String> readLines(InputStream in) throws IOException {
    List<String> lines = new ArrayList<>();
    // The script writes ASCII; messages of PowerShell itself come in the console code page
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, Charset.defaultCharset()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    return lines;
  }

  private synchronized Path script() throws IOException {
    if (script == null || !Files.isRegularFile(script)) {
      Path file = Files.createTempFile(AppProperties.FILE_CACHE_DIR, "proscan-", ".ps1"); // NON-NLS
      try (InputStream in = JetRunner.class.getResourceAsStream(SCRIPT)) {
        if (in == null) {
          throw new IOException("Missing " + SCRIPT); // NON-NLS
        }
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
      }
      script = file;
    }
    return script;
  }
}
