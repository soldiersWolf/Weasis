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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.util.ThreadUtil;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.BusyException;
import org.weasis.dicom.explorer.proscan.ProScanDatabase.UnavailableException;

/**
 * Opens the ProScan database through the Jet engine shipped with Windows. ProScan is a Jet 3
 * (Access 97) application: going through Jet, rather than writing the file directly, keeps the
 * record locks it shares with ProScan. Jet exists only in 32 bits, so the requests run in the
 * 32-bit PowerShell of Windows, and nothing has to be installed.
 *
 * <p>Starting PowerShell takes seconds on a small computer, so one PowerShell is kept running as
 * a worker that answers the requests one after another, and stopped after a few idle minutes to
 * give its memory back.
 */
final class JetRunner implements ProScanDatabase.Runner {

  private static final String SCRIPT = "jet.ps1"; // NON-NLS
  private static final String END = "."; // NON-NLS
  // Put in the queue when the worker stops; never a line of the worker
  private static final String STOPPED = "\u0000stopped"; // NON-NLS
  private static final long REACH_SECONDS = 5;
  private static final long IDLE_MILLIS = 3 * 60_000L;
  private static final ExecutorService REACH_EXECUTOR =
      Executors.newCachedThreadPool(Thread.ofVirtual().name("ProScan reach").factory()); // NON-NLS
  private static final ScheduledExecutorService IDLE_CHECK =
      Executors.newSingleThreadScheduledExecutor(
          ThreadUtil.namedDaemonThreadFactory("ProScan worker idle", true)); // NON-NLS

  private Path script;
  private volatile Process worker;
  private BufferedWriter toWorker;
  private BlockingQueue<String> fromWorker;
  private long lastUse;

  JetRunner() {
    IDLE_CHECK.scheduleWithFixedDelay(this::stopIfIdle, 1, 1, TimeUnit.MINUTES);
    // Without the lock: a request may hold it while the application closes
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  Process running = worker;
                  if (running != null) {
                    running.destroyForcibly();
                  }
                },
                "ProScan worker stop")); // NON-NLS
  }

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
  public synchronized List<List<String>> run(Map<String, String> args) throws IOException {
    Path db = Path.of(args.get("PROSCAN_DB"));
    boolean write = "write".equals(args.get("PROSCAN_MODE")); // NON-NLS
    checkReachable(db);
    startWorker();
    lastUse = System.currentTimeMillis();
    try {
      toWorker.write(requestLine(args));
      toWorker.newLine();
      toWorker.flush();
    } catch (IOException e) {
      stopWorker();
      throw new IOException(ProScanMessages.getString("error.worker"), e);
    }
    List<String> lines = readAnswer(args);
    lastUse = System.currentTimeMillis();
    try {
      return ProScanDatabase.parse(lines);
    } catch (BusyException e) {
      throw e;
    } catch (IOException e) {
      // Jet writes a lock file next to the database: a read-only folder refuses any change
      if (write && db.getParent() != null && !Files.isWritable(db.getParent())) {
        throw new IOException(ProScanMessages.format("error.readonly", db.getParent()), e);
      }
      throw e;
    }
  }

  /** The arguments as one line: KEY=value pairs, each value in Base64 of UTF-8. */
  static String requestLine(Map<String, String> args) {
    return args.entrySet().stream()
        .map(
            e ->
                e.getKey()
                    + "="
                    + Base64.getEncoder()
                        .encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8)))
        .collect(Collectors.joining(" "));
  }

  private List<String> readAnswer(Map<String, String> args) throws IOException {
    List<String> lines = new ArrayList<>();
    long deadline =
        System.currentTimeMillis() + timeoutSeconds(args.get("PROSCAN_MODE")) * 1000L;
    try {
      while (true) {
        long left = deadline - System.currentTimeMillis();
        String line = left > 0 ? fromWorker.poll(left, TimeUnit.MILLISECONDS) : null;
        if (line == null) {
          // A request hanging on the network: the worker is stopped and started again next time
          stopWorker();
          throw new UnavailableException(
              ProScanMessages.format("error.timeout", args.get("PROSCAN_DB")));
        }
        if (STOPPED.equals(line)) {
          stopWorker();
          throw new IOException(
              ProScanMessages.getString("error.worker") + " " + String.join(" ", lines));
        }
        if (END.equals(line)) {
          return lines;
        }
        lines.add(line);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stopWorker();
      throw new IOException(e);
    }
  }

  private void startWorker() throws IOException {
    if (worker != null && worker.isAlive()) {
      return;
    }
    Path exe =
        executable().orElseThrow(() -> new IOException(ProScanMessages.getString("error.os")));
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
    builder.environment().put("PROSCAN_SERVER", "1"); // NON-NLS
    Process process = builder.start();
    BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    // The worker writes ASCII; messages of PowerShell itself come in the console code page
    Thread.ofVirtual()
        .name("ProScan worker output") // NON-NLS
        .start(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  queue.add(line);
                }
              } catch (IOException e) {
                // The worker stopped
              }
              queue.add(STOPPED);
            });
    worker = process;
    fromWorker = queue;
    toWorker =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII));
  }

  private synchronized void stopIfIdle() {
    if (worker != null && System.currentTimeMillis() - lastUse > IDLE_MILLIS) {
      stopWorker();
    }
  }

  private synchronized void stopWorker() {
    if (worker != null) {
      try {
        // Closing its input ends the worker loop; a stuck worker is killed
        toWorker.close();
      } catch (IOException e) {
        // Already gone
      }
      worker.destroyForcibly();
      worker = null;
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

  private Path script() throws IOException {
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
