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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.MediaSeriesGroupNode;
import org.weasis.core.api.net.auth.AuthMethod;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.pref.node.AbstractDicomNode;
import org.weasis.dicom.explorer.pref.node.DefaultDicomNode;
import org.weasis.dicom.explorer.pref.node.DicomWebNode;
import org.weasis.dicom.explorer.rs.RsQueryParams;
import org.weasis.dicom.param.DicomParam;
import org.weasis.dicom.param.DicomState;

/**
 * Runs the same study query on several archives at once and merges the answers into one model,
 * remembering which archive returned each study so that it is retrieved from that archive.
 */
public class MultiArchiveSearch {
  private static final Logger LOGGER = LoggerFactory.getLogger(MultiArchiveSearch.class);

  /** Queries the studies of one DIMSE archive (C-FIND). */
  public interface DimseQuery {
    DimseAnswer find(DefaultDicomNode archive) throws Exception;
  }

  /**
   * Studies returned by a DIMSE archive, with the error message when the query did not complete.
   */
  public record DimseAnswer(List<Attributes> items, String error) {
    public static DimseAnswer of(DicomState state) {
      int status = state.getStatus();
      boolean ok = status == Status.Success || status == Status.Pending;
      return new DimseAnswer(state.getDicomRSP(), ok ? null : String.valueOf(state.getMessage()));
    }
  }

  private final DicomModel model = new DicomModel();
  private final Map<String, AbstractDicomNode> archiveByStudy = new ConcurrentHashMap<>();
  private final Map<AbstractDicomNode, AuthMethod> authByArchive;
  private final Map<AbstractDicomNode, String> failures = new ConcurrentHashMap<>();
  private final BiConsumer<DicomModel, Attributes> populate;

  public MultiArchiveSearch(Map<AbstractDicomNode, AuthMethod> authByArchive) {
    this(authByArchive, RsQuery::populateDicomModel);
  }

  /** The populate function adds one study answer to the merged model. */
  MultiArchiveSearch(
      Map<AbstractDicomNode, AuthMethod> authByArchive,
      BiConsumer<DicomModel, Attributes> populate) {
    this.authByArchive = Map.copyOf(authByArchive);
    this.populate = populate;
  }

  /**
   * Queries every archive in parallel and blocks until all have answered or failed.
   *
   * @param dimse the study query of a DIMSE archive
   * @param web builds the QIDO-RS query of a DICOMweb archive filling the given model
   */
  public void run(
      Collection<AbstractDicomNode> archives,
      DimseQuery dimse,
      Function<WebQueryTarget, RsQuery> web) {
    if (archives.isEmpty()) {
      return;
    }
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<AbstractDicomNode, Future<?>> tasks = new LinkedHashMap<>();
      for (AbstractDicomNode archive : archives) {
        tasks.put(archive, pool.submit(() -> query(archive, dimse, web)));
      }
      for (Map.Entry<AbstractDicomNode, Future<?>> task : tasks.entrySet()) {
        try {
          task.getValue().get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (ExecutionException e) {
          // An error, not an exception, escaped the query: still report the archive as failed
          LOGGER.error("Query of the archive {}", task.getKey(), e.getCause());
          failures.putIfAbsent(task.getKey(), String.valueOf(e.getCause()));
        }
      }
    }
  }

  private void query(
      AbstractDicomNode archive, DimseQuery dimse, Function<WebQueryTarget, RsQuery> web) {
    try {
      if (archive instanceof DefaultDicomNode node) {
        DimseAnswer answer = dimse.find(node);
        List<Attributes> items = answer.items();
        synchronized (model) {
          if (items != null) {
            for (Attributes item : items) {
              if (item != null) {
                populate.accept(model, item);
                String uid = item.getString(Tag.StudyInstanceUID);
                if (uid != null) {
                  archiveByStudy.putIfAbsent(uid, archive);
                }
              }
            }
          }
        }
        if (answer.error() != null) {
          failures.put(archive, answer.error());
        }
      } else if (archive instanceof DicomWebNode node) {
        RsQuery query = web.apply(new WebQueryTarget(node, authByArchive.get(node), model));
        // RsQuery fills the shared model while it reads the answer: one web archive at a time
        synchronized (model) {
          Set<String> before = studyUids();
          query.call();
          for (String uid : studyUids()) {
            if (!before.contains(uid)) {
              archiveByStudy.putIfAbsent(uid, archive);
            }
          }
        }
      }
    } catch (Exception e) {
      LOGGER.error("Query of the archive {}", archive, e);
      failures.put(archive, String.valueOf(e.getMessage()));
    }
  }

  private Set<String> studyUids() {
    Set<String> uids = new HashSet<>();
    for (MediaSeriesGroup patient : model.getChildren(MediaSeriesGroupNode.rootNode)) {
      for (MediaSeriesGroup study : model.getChildren(patient)) {
        String uid = TagD.getTagValue(study, Tag.StudyInstanceUID, String.class);
        if (uid != null) {
          uids.add(uid);
        }
      }
    }
    return uids;
  }

  public DicomModel getModel() {
    return model;
  }

  /** Archive that returned the study, empty when the study is unknown. */
  public Optional<AbstractDicomNode> getArchive(String studyUid) {
    return Optional.ofNullable(archiveByStudy.get(studyUid));
  }

  public AuthMethod getAuthMethod(AbstractDicomNode archive) {
    return authByArchive.get(archive);
  }

  /** Archives that could not be queried, with the reason. */
  public Map<AbstractDicomNode, String> getFailures() {
    return Map.copyOf(failures);
  }

  /** Splits a retrieve selection by the archive that returned each study. */
  public Map<AbstractDicomNode, RetrieveSelection> splitByArchive(RetrieveSelection selection) {
    Map<AbstractDicomNode, RetrieveSelection> split = new LinkedHashMap<>();
    for (String studyUid : selection.getStudyUids()) {
      AbstractDicomNode archive = archiveByStudy.get(studyUid);
      if (archive == null) {
        LOGGER.warn("No archive for the study {}", studyUid);
        continue;
      }
      RetrieveSelection part = split.computeIfAbsent(archive, _ -> new RetrieveSelection());
      Set<String> series = selection.getSeriesUids(studyUid);
      if (series.isEmpty()) {
        part.addStudy(studyUid);
      } else {
        series.forEach(s -> part.addSeries(studyUid, s));
      }
    }
    return split;
  }

  /** What a DICOMweb archive query needs: the node, its authentication and the model to fill. */
  public record WebQueryTarget(DicomWebNode node, AuthMethod authMethod, DicomModel model) {
    public Properties baseProperties() {
      Properties props = new Properties();
      props.setProperty(RsQueryParams.P_DICOMWEB_URL, node.getUrl().toString());
      return props;
    }
  }

  /** Copies the query parameters so that each archive gets its own list. */
  static List<DicomParam> copy(List<DicomParam> params) {
    return new ArrayList<>(params);
  }
}
