package io.cmdb.collector.argo.application;

import io.cmdb.collector.argo.ArgoScopeObserved;
import io.cmdb.collector.argo.Destination;
import io.cmdb.collector.argo.domain.ArgoHub;
import io.cmdb.collector.rawevent.application.RawEventRecorder;
import io.cmdb.collector.rawevent.domain.ContentFingerprint;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.syncrun.application.SyncRunTracker;
import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncOutcome;
import io.cmdb.collector.syncrun.domain.SyncType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Polls each site's Argo CD through the Argo hub (PLAN.md 3.3).
 *
 * <p>external_event_id: {@code argo:{site}:app:{uid}:{content fingerprint}}. A new event exists
 * only when the Application content changes; the resource-tree is fetched immediately when the
 * Application changes, and fully refreshed every tree-interval as compensation (its event id also
 * has an hour bucket, so an unchanged tree is landed again once an hour).
 */
@Service
public class ArgoCollector {

  static final String PROVIDER = "ARGO";
  // Even if the cluster list is unchanged, regenerate the event every hour so an argo_cluster_name
  // missed because clusters did not exist yet can be filled in
  private static final DateTimeFormatter HOUR_BUCKET =
      DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
  private static final Logger log = LoggerFactory.getLogger(ArgoCollector.class);

  private final ArgoHub hub;
  private final RawEventRecorder recorder;
  private final SyncRunTracker tracker;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Duration treeInterval;
  private final Map<String, Instant> lastTreeFetch = new ConcurrentHashMap<>();

  public ArgoCollector(
      ArgoHub hub,
      RawEventRecorder recorder,
      SyncRunTracker tracker,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${collector.argo.tree-interval:5m}") Duration treeInterval) {
    this.hub = hub;
    this.recorder = recorder;
    this.tracker = tracker;
    this.events = events;
    this.clock = clock;
    this.treeInterval = treeInterval;
  }

  /** Polls the Applications of all sites; a failure at one site does not affect the others. */
  public CollectionResult collectApplications() {
    CollectionResult result = CollectionResult.none();
    for (String site : sites()) {
      result =
          result.with(tracker.track("ARGO:" + site, SyncType.POLL, () -> syncApplications(site)));
    }
    return result;
  }

  /** Polls the clusters registered in Argo at all sites. */
  public CollectionResult collectClusters() {
    CollectionResult result = CollectionResult.none();
    for (String site : sites()) {
      result =
          result.with(
              tracker.track("ARGO_CLUSTERS:" + site, SyncType.POLL, () -> syncClusters(site)));
    }
    return result;
  }

  /** Site list (name and region). An event identical to the previous one is deduplicated. */
  public CollectionResult collectSites() {
    return CollectionResult.none()
        .with(tracker.track("ARGO_SITES", SyncType.POLL, this::syncSites));
  }

  private SyncOutcome syncSites() {
    var items = JsonNodeFactory.instance.arrayNode();
    for (var site : hub.siteDetails()) {
      ObjectNode item = items.addObject();
      item.put("code", site.code());
      if (site.name() != null) {
        item.put("name", site.name());
      }
      if (site.region() != null) {
        item.put("region", site.region());
      }
    }
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.set("items", items);
    recorder.record(
        new RawEvent(
            PROVIDER,
            "SITE_SNAPSHOT",
            "argo:sites:%s".formatted(ContentFingerprint.of(body)),
            "argo:sites",
            body));
    return SyncOutcome.success(items.size(), null);
  }

  private List<String> sites() {
    try {
      return hub.sites();
    } catch (RuntimeException e) {
      log.error("cannot list sites from Argo hub", e);
      return List.of();
    }
  }

  private SyncOutcome syncApplications(String site) {
    JsonNode items = hub.applications(site).path("items");
    Instant now = clock.instant();
    List<Destination> destinations = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int newEvents = 0;
    int trees = 0;
    int count = 0;

    for (JsonNode app : items) {
      String uid = app.path("metadata").path("uid").asString("");
      String name = app.path("metadata").path("name").asString("");
      if (uid.isEmpty()) {
        errors.add("application without metadata.uid: " + name);
        continue;
      }
      count++;
      if (!app.path("status").path("summary").path("isAppOfApps").asBoolean(false)) {
        JsonNode dest = app.path("spec").path("destination");
        destinations.add(
            new Destination(
                dest.path("server").asString(null),
                dest.path("name").asString(null),
                dest.path("namespace").asString(null)));
      }

      ObjectNode projected = ArgoPayloads.application(app);
      boolean changed =
          recorder.record(
              new RawEvent(
                  PROVIDER,
                  "APPLICATION_SNAPSHOT",
                  "argo:%s:app:%s:%s".formatted(site, uid, ContentFingerprint.of(projected)),
                  orderingKey(site, uid),
                  ArgoPayloads.envelope(site, now, projected)));
      if (changed) {
        newEvents++;
      }

      if (changed || treeDue(site, uid, now)) {
        try {
          collectTree(site, app, uid, now);
          trees++;
        } catch (RuntimeException e) {
          errors.add("resource-tree %s: %s".formatted(name, e.getMessage()));
        }
      }
    }

    events.publishEvent(new ArgoScopeObserved(site, List.copyOf(destinations)));
    ObjectNode cursor = JsonNodeFactory.instance.objectNode();
    cursor.put("applications", count);
    cursor.put("newEvents", newEvents);
    cursor.put("treesFetched", trees);
    cursor.put("errors", errors.size());
    return errors.isEmpty()
        ? SyncOutcome.success(count, cursor)
        : SyncOutcome.partial(count, String.join("; ", errors), cursor);
  }

  private void collectTree(String site, JsonNode app, String uid, Instant now) {
    JsonNode tree =
        hub.resourceTree(
            site,
            app.path("metadata").path("name").asString(),
            app.path("metadata").path("namespace").asString(null),
            app.path("spec").path("project").asString(null));
    ObjectNode projected = ArgoPayloads.resourceTree(tree);
    // The hour bucket regenerates the event every hour even when the tree is unchanged: an
    // Application whose cluster was only registered after its tree was processed gets its
    // resources linked by the next one
    recorder.record(
        new RawEvent(
            PROVIDER,
            "RESOURCE_TREE_SNAPSHOT",
            "argo:%s:tree:%s:%s:%s"
                .formatted(
                    site,
                    uid,
                    ContentFingerprint.of(projected),
                    HOUR_BUCKET.format(clock.instant())),
            orderingKey(site, uid),
            ArgoPayloads.treeEnvelope(
                site, now, uid, app.path("metadata").path("name").asString(""), projected)));
    lastTreeFetch.put(site + ':' + uid, now);
  }

  private boolean treeDue(String site, String uid, Instant now) {
    Instant last = lastTreeFetch.get(site + ':' + uid);
    return last == null || Duration.between(last, now).compareTo(treeInterval) >= 0;
  }

  private SyncOutcome syncClusters(String site) {
    var projected = JsonNodeFactory.instance.arrayNode();
    for (JsonNode c : hub.clusters(site).path("items")) {
      projected.add(ArgoPayloads.cluster(c));
    }
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.set("items", projected);
    recorder.record(
        new RawEvent(
            PROVIDER,
            "CLUSTER_SNAPSHOT",
            "argo:%s:clusters:%s:%s"
                .formatted(site, ContentFingerprint.of(body), HOUR_BUCKET.format(clock.instant())),
            "argo:%s:clusters".formatted(site),
            ArgoPayloads.envelope(site, clock.instant(), body)));
    return SyncOutcome.success(projected.size(), null);
  }

  private static String orderingKey(String site, String uid) {
    return "argo:%s:%s".formatted(site, uid);
  }
}
