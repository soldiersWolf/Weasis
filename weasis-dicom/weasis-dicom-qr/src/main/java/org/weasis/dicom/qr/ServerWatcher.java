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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.service.QueryRetrieveLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.net.auth.AuthMethod;
import org.weasis.core.api.net.auth.OAuth2ServiceFactory;
import org.weasis.core.api.service.WProperties;
import org.weasis.dicom.codec.display.CharsetEncoding;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.main.DicomExplorer;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode.RetrieveType;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode.UsageType;
import org.weasis.dicom.explorer.pref.node.AuthenticationPersistence;
import org.weasis.dicom.explorer.pref.node.DefaultDicomNode;
import org.weasis.dicom.explorer.pref.node.DicomWebNode;
import org.weasis.dicom.explorer.pref.node.DicomWebNode.WebType;
import org.weasis.dicom.explorer.rs.RsQueryParams;
import org.weasis.dicom.op.CFind;
import org.weasis.dicom.param.DicomParam;
import org.weasis.dicom.tool.DicomListener;

/**
 * Pulls the studies of the day from the archives ticked on the sources page: every few minutes
 * each archive is queried for today's studies, and the studies not yet in the explorer are
 * retrieved from the archive that returned them, like a watched folder. Archives that need an
 * interactive sign-in are skipped, they can still be searched by hand.
 */
public final class ServerWatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerWatcher.class);

  static final String P_ARCHIVES = "sync.archives"; // NON-NLS
  static final String P_MINUTES = "sync.minutes"; // NON-NLS
  private static final String SEPARATOR = "\u001f";
  private static final int DEFAULT_MINUTES = 2;
  private static final int STUDY_LIMIT = 500;

  /** Outcome of the last check of an archive. */
  public record Status(Instant checkedAt, int found, int retrieved, String error) {}

  private static final ServerWatcher INSTANCE = new ServerWatcher();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "Archive sync"); // NON-NLS
            t.setDaemon(true);
            return t;
          });
  private final ExecutorService retrieveExecutor = Executors.newFixedThreadPool(2);
  private final Set<String> requested = ConcurrentHashMap.newKeySet();
  private final Map<String, Status> statusByArchive = new ConcurrentHashMap<>();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private ScheduledFuture<?> schedule;
  private DicomListener dicomListener;

  private ServerWatcher() {}

  public static ServerWatcher getInstance() {
    return INSTANCE;
  }

  private static WProperties prefs() {
    return DicomQrView.getPersistence();
  }

  /** Descriptions of the archives synchronized automatically. */
  public Set<String> getSyncedArchives() {
    Set<String> names = new LinkedHashSet<>();
    for (String s : prefs().getProperty(P_ARCHIVES, "").split(SEPARATOR)) {
      if (!s.isBlank()) {
        names.add(s);
      }
    }
    return names;
  }

  public void setSynced(AbstractDicomNode archive, boolean synced) {
    Set<String> names = getSyncedArchives();
    if (synced) {
      names.add(archive.getDescription());
    } else {
      names.remove(archive.getDescription());
      statusByArchive.remove(archive.getDescription());
    }
    prefs().setProperty(P_ARCHIVES, String.join(SEPARATOR, names));
    fireChange();
    if (synced) {
      checkNow();
    }
  }

  public int getMinutes() {
    try {
      String value = prefs().getProperty(P_MINUTES, String.valueOf(DEFAULT_MINUTES));
      return Math.max(1, Integer.parseInt(value));
    } catch (NumberFormatException e) {
      return DEFAULT_MINUTES;
    }
  }

  public synchronized void setMinutes(int minutes) {
    prefs().setProperty(P_MINUTES, String.valueOf(Math.max(1, minutes)));
    start();
  }

  public Status getStatus(AbstractDicomNode archive) {
    return statusByArchive.get(archive.getDescription());
  }

  /** Runs the listener, on a background thread, whenever an archive was checked. */
  public void addChangeListener(Runnable listener) {
    listeners.add(listener);
  }

  private void fireChange() {
    listeners.forEach(Runnable::run);
  }

  /** (Re)starts the periodic check; the first one runs shortly after start up. */
  public synchronized void start() {
    if (schedule != null) {
      schedule.cancel(false);
    }
    long minutes = getMinutes();
    schedule =
        scheduler.scheduleWithFixedDelay(this::checkSafely, 30, minutes * 60, TimeUnit.SECONDS);
  }

  public void checkNow() {
    scheduler.execute(this::checkSafely);
  }

  private void checkSafely() {
    try {
      check();
    } catch (RuntimeException e) {
      LOGGER.error("Archive synchronization", e);
    }
  }

  /** Archives ticked for synchronization that are still configured. */
  static List<AbstractDicomNode> configuredArchives() {
    List<AbstractDicomNode> archives =
        new ArrayList<>(
            AbstractDicomNode.loadDicomNodes(AbstractDicomNode.Type.DICOM, UsageType.RETRIEVE));
    archives.addAll(
        AbstractDicomNode.loadDicomNodes(
            AbstractDicomNode.Type.WEB, UsageType.RETRIEVE, WebType.QIDORS));
    return archives;
  }

  private void check() {
    Set<String> names = getSyncedArchives();
    if (names.isEmpty()) {
      return;
    }
    DicomModel explorerModel = explorerModel();
    if (explorerModel == null) {
      return; // The explorer is not ready yet
    }
    List<AbstractDicomNode> archives = new ArrayList<>();
    Map<AbstractDicomNode, AuthMethod> auths = new HashMap<>();
    for (AbstractDicomNode archive : configuredArchives()) {
      if (!names.contains(archive.getDescription())) {
        continue;
      }
      if (archive instanceof DicomWebNode web) {
        AuthMethod auth = AuthenticationPersistence.getAuthMethod(web.getAuthMethodUid());
        if (!OAuth2ServiceFactory.NO_AUTH.equals(auth)) {
          // A sign-in may need the user: never start it from a background check
          record(archive, 0, 0, MultiArchiveMessages.getString("sync.signin"));
          continue;
        }
        auths.put(web, auth);
      }
      archives.add(archive);
    }
    if (archives.isEmpty()) {
      return;
    }

    DefaultDicomNode callingNode = callingNode();
    String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    List<DicomParam> params = new ArrayList<>(List.of(new DicomParam(Tag.StudyDate, today)));
    List<DicomParam> findParams = new ArrayList<>(params);
    DicomQrView.addStudyFindParams(findParams, CharsetEncoding.ISO_IR_192);

    MultiArchiveSearch search = new MultiArchiveSearch(auths);
    search.run(
        archives,
        node -> {
          if (callingNode == null) {
            throw new IllegalStateException(Messages.getString("RetrieveTask.no_calling_node"));
          }
          return MultiArchiveSearch.DimseAnswer.of(
              CFind.process(
                  DicomQrView.buildFindParams(false),
                  callingNode.getDicomNodeWithOnlyAET(),
                  node.getDicomNode(),
                  STUDY_LIMIT,
                  QueryRetrieveLevel.STUDY,
                  findParams.toArray(new DicomParam[0])));
        },
        target -> {
          Properties props = target.baseProperties();
          props.setProperty(
              RsQueryParams.P_PAGE_EXT, "&limit=%d&offset=0".formatted(STUDY_LIMIT)); // NON-NLS
          return new RsQuery(
              target.model(),
              props,
              new ArrayList<>(params),
              target.authMethod(),
              target.node().getHeaders());
        });

    RetrieveSelection fresh = new RetrieveSelection();
    for (String uid : search.getStudyUids()) {
      if (explorerModel.getStudyNode(uid) == null && requested.add(uid)) {
        fresh.addStudy(uid);
      }
    }
    Map<AbstractDicomNode, RetrieveSelection> split = search.splitByArchive(fresh);
    RetrieveHost host = new BackgroundHost(search.getModel(), callingNode, this);
    for (AbstractDicomNode archive : archives) {
      String failure = search.getFailures().get(archive);
      RetrieveSelection part = split.get(archive);
      int found =
          (int)
              search.getStudyUids().stream()
                  .filter(uid -> search.getArchive(uid).orElse(null) == archive)
                  .count();
      DicomWebNode wadoRs = null;
      if (part != null && failure == null && archive instanceof DicomWebNode web) {
        wadoRs = silentWadoRsNode(web);
        if (wadoRs == null) {
          failure = MultiArchiveMessages.getString("sync.nowado");
          part.getStudyUids().forEach(requested::remove); // Try again once it is configured
        }
      }
      int fetched = part == null || failure != null ? 0 : part.getStudyUids().size();
      record(archive, found, fetched, failure);
      if (part != null && failure == null) {
        LOGGER.info("Retrieving {} new studies from {}", part.getStudyUids().size(), archive);
        retrieveExecutor.execute(
            new RetrieveTask(
                part,
                explorerModel,
                host,
                new RetrieveTask.RetrieveSource(archive, wadoRs, search.getAuthMethod(archive))));
      }
    }
  }

  /**
   * WADO-RS node of the same host as the QIDO-RS archive, chosen without asking: a background check
   * must never open a dialog.
   */
  private static DicomWebNode silentWadoRsNode(DicomWebNode archive) {
    if (archive.getWebType() != WebType.QIDORS || archive.getUrl() == null) {
      return null;
    }
    String host = RetrieveTask.getHostname(archive.getUrl().getHost());
    for (AbstractDicomNode n :
        AbstractDicomNode.loadDicomNodes(
            AbstractDicomNode.Type.WEB, UsageType.RETRIEVE, WebType.WADORS)) {
      if (n instanceof DicomWebNode wn
          && wn.getUrl() != null
          && RetrieveTask.getHostname(wn.getUrl().getHost()).contains(host)) {
        return wn;
      }
    }
    return null;
  }

  private void record(AbstractDicomNode archive, int found, int retrieved, String error) {
    statusByArchive.put(
        archive.getDescription(), new Status(Instant.now(), found, retrieved, error));
    fireChange();
  }

  private static DicomModel explorerModel() {
    DataExplorerView view = GuiUtils.getUICore().getExplorerPlugin(DicomExplorer.NAME);
    return view != null && view.getDataExplorerModel() instanceof DicomModel model ? model : null;
  }

  /** Calling node chosen last in the query dialog, otherwise the first one configured. */
  private static DefaultDicomNode callingNode() {
    List<AbstractDicomNode> nodes =
        AbstractDicomNode.loadDicomNodes(AbstractDicomNode.Type.DICOM_CALLING, UsageType.RETRIEVE);
    String last = prefs().getProperty(DicomQrView.LAST_CALLING_NODE);
    DefaultDicomNode first = null;
    for (AbstractDicomNode node : nodes) {
      if (node instanceof DefaultDicomNode n) {
        if (first == null) {
          first = n;
        }
        if (last != null && (last.equals(n.getId()) || last.equals(n.getDescription()))) {
          return n;
        }
      }
    }
    return first;
  }

  private static RetrieveType retrieveType() {
    try {
      return RetrieveType.valueOf(
          prefs().getProperty(DicomQrView.LAST_RETRIEVE_TYPE, RetrieveType.CGET.name()));
    } catch (IllegalArgumentException e) {
      return RetrieveType.CGET;
    }
  }

  private synchronized DicomListener listener() {
    if (dicomListener == null) {
      try {
        dicomListener = new DicomListener(DicomQrView.getSessionTempFolder());
      } catch (RuntimeException e) {
        LOGGER.error("Cannot start the DICOM listener of the archive synchronization", e);
      }
    }
    return dicomListener;
  }

  /** Retrieve settings of a background check: the same as the query dialog, without a dialog. */
  private record BackgroundHost(
      DicomModel queryModel, DefaultDicomNode callingNode, ServerWatcher watcher)
      implements RetrieveHost {
    @Override
    public DicomModel getQueryModel() {
      return queryModel;
    }

    @Override
    public DefaultDicomNode getCallingNode() {
      return callingNode;
    }

    @Override
    public RetrieveType getRetrieveType() {
      return retrieveType();
    }

    @Override
    public DicomListener getDicomListener() {
      return getRetrieveType() == RetrieveType.CMOVE ? watcher.listener() : null;
    }

    @Override
    public Component getDialogParent() {
      return null;
    }
  }
}
