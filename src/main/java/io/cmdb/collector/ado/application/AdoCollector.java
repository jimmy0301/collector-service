package io.cmdb.collector.ado.application;

import io.cmdb.collector.ado.domain.AdoHub;
import io.cmdb.collector.rawevent.application.RawEventRecorder;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.syncrun.application.SyncRunTracker;
import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncOutcome;
import io.cmdb.collector.syncrun.domain.SyncType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Polls the Azure DevOps Release deployments (one deployment = release + stage + attempt) of the
 * configured projects, and the release behind every changed deployment.
 *
 * <p>Incremental: each round asks only for deployments modified since a watermark (kept in memory;
 * after a restart it starts again at {@code now - lookback}, which is safe because the event ids
 * below make a repeated fetch a no-op). The watermark moves forward only after a round without
 * errors and stays {@code overlap} behind the newest modification, so a late-committed change is
 * still seen.
 *
 * <p>external_event_id: {@code ado:{project}:deployment:{id}:{lastModifiedOn}} and {@code
 * ado:{project}:release:{id}:{modifiedOn}}. Both kinds of one release share the ordering key {@code
 * ado:{project}:release:{releaseId}}.
 */
@Service
public class AdoCollector {

  static final String PROVIDER = "ADO";

  private final AdoHub hub;
  private final RawEventRecorder recorder;
  private final SyncRunTracker tracker;
  private final Clock clock;
  private final List<String> projects;
  private final Duration lookback;
  private final Duration overlap;
  private final Map<String, Instant> watermarks = new ConcurrentHashMap<>();

  /** release id → the newest deployment modification the release was fetched for */
  private final Map<String, Instant> releasesFetchedFor = new ConcurrentHashMap<>();

  public AdoCollector(
      AdoHub hub,
      RawEventRecorder recorder,
      SyncRunTracker tracker,
      Clock clock,
      @Value("${collector.ado.projects:}") List<String> projects,
      @Value("${collector.ado.organization:}") String organization,
      @Value("${collector.ado.lookback:7d}") Duration lookback,
      @Value("${collector.ado.overlap:5m}") Duration overlap) {
    this.hub = hub;
    this.recorder = recorder;
    this.tracker = tracker;
    this.clock = clock;
    this.projects =
        organization == null || organization.isBlank()
            ? List.of()
            : projects.stream().filter(p -> !p.isBlank()).toList();
    this.lookback = lookback;
    this.overlap = overlap;
  }

  /** Polls every configured project; a failure in one does not affect the others. */
  public CollectionResult collectReleases() {
    CollectionResult result = CollectionResult.none();
    for (String project : projects) {
      result = result.with(tracker.track("ADO:" + project, SyncType.POLL, () -> sync(project)));
    }
    return result;
  }

  private SyncOutcome sync(String project) {
    Instant now = clock.instant();
    Instant since = watermarks.getOrDefault(project, now.minus(lookback));
    List<JsonNode> deployments = hub.deploymentsModifiedSince(project, since);

    List<String> errors = new ArrayList<>();
    int newEvents = 0;
    Instant newest = null;

    for (JsonNode d : deployments) {
      String id = d.path("id").asString("");
      String releaseId = d.path("release").path("id").asString("");
      String modified = d.path("lastModifiedOn").asString("");
      if (id.isEmpty() || releaseId.isEmpty() || modified.isEmpty()) {
        errors.add("deployment without id, release.id or lastModifiedOn: " + id);
        continue;
      }
      Instant modifiedAt = Instant.parse(modified);
      newest = newest == null || modifiedAt.isAfter(newest) ? modifiedAt : newest;

      if (recorder.record(
          new RawEvent(
              PROVIDER,
              "RELEASE_DEPLOYMENT",
              "ado:%s:deployment:%s:%s".formatted(project, id, modified),
              orderingKey(project, releaseId),
              AdoPayloads.envelope(project, now, AdoPayloads.deployment(d))))) {
        newEvents++;
      }

      // The release only needs fetching again when one of its deployments changed since
      String key = project + ':' + releaseId;
      Instant fetchedFor = releasesFetchedFor.get(key);
      if (fetchedFor == null || modifiedAt.isAfter(fetchedFor)) {
        try {
          JsonNode release = hub.release(project, releaseId);
          recorder.record(
              new RawEvent(
                  PROVIDER,
                  "RELEASE",
                  "ado:%s:release:%s:%s"
                      .formatted(project, releaseId, release.path("modifiedOn").asString("")),
                  orderingKey(project, releaseId),
                  AdoPayloads.envelope(project, now, AdoPayloads.release(release))));
          releasesFetchedFor.put(key, modifiedAt);
        } catch (RuntimeException e) {
          errors.add("release %s: %s".formatted(releaseId, e.getMessage()));
        }
      }
    }

    if (errors.isEmpty() && newest != null) {
      watermarks.put(project, newest.minus(overlap));
    }
    ObjectNode cursor = JsonNodeFactory.instance.objectNode();
    cursor.put("since", since.toString());
    cursor.put("deployments", deployments.size());
    cursor.put("newEvents", newEvents);
    cursor.put("errors", errors.size());
    return errors.isEmpty()
        ? SyncOutcome.success(deployments.size(), cursor)
        : SyncOutcome.partial(deployments.size(), String.join("; ", errors), cursor);
  }

  private static String orderingKey(String project, String releaseId) {
    return "ado:%s:release:%s".formatted(project, releaseId);
  }
}
