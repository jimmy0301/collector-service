package io.cmdb.collector.k8s.application;

import io.cmdb.collector.k8s.domain.Cluster;
import io.cmdb.collector.k8s.domain.K8sHub;
import io.cmdb.collector.k8s.domain.ResourceKind;
import io.cmdb.collector.rawevent.application.RawEventRecorder;
import io.cmdb.collector.rawevent.domain.ContentFingerprint;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.syncrun.application.SyncRunTracker;
import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncOutcome;
import io.cmdb.collector.syncrun.domain.SyncType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Polls each site's clusters through the K8s integration service (PLAN.md 3.4, 9.3). Scope is
 * limited to the namespaces Argo covers ({@link ManagedNamespaces}, updated by the event published
 * by the argo context).
 *
 * <p>external_event_id: {@code k8s:{clusterId}:{uid}:{resourceVersion}}; ordering_key: {@code
 * k8s:{clusterId}:{uid}}. A list poll cannot see deletions; deletions are detected through the
 * RESOURCE_LIST event (see recordList).
 */
@Service
public class K8sCollector {

  static final String PROVIDER = "K8S";
  private static final Logger log = LoggerFactory.getLogger(K8sCollector.class);
  private static final DateTimeFormatter HOUR_BUCKET =
      DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
  private static final List<ResourceKind> WORKLOAD_KINDS =
      List.of(ResourceKind.DEPLOYMENT, ResourceKind.REPLICASET, ResourceKind.POD);

  private final K8sHub hub;
  private final RawEventRecorder recorder;
  private final SyncRunTracker tracker;
  private final ManagedNamespaces scope;
  private final Clock clock;
  private final Map<String, List<Cluster>> clusterCache = new ConcurrentHashMap<>();

  public K8sCollector(
      K8sHub hub,
      RawEventRecorder recorder,
      SyncRunTracker tracker,
      ManagedNamespaces scope,
      Clock clock) {
    this.hub = hub;
    this.recorder = recorder;
    this.tracker = tracker;
    this.scope = scope;
    this.clock = clock;
  }

  /** Refreshes the cluster list and lands a snapshot. */
  public CollectionResult collectClusters() {
    CollectionResult result = CollectionResult.none();
    for (String site : sites()) {
      result =
          result.with(
              tracker.track("K8S_CLUSTERS:" + site, SyncType.POLL, () -> syncClusters(site)));
    }
    return result;
  }

  /** Deployment, ReplicaSet, Pod. */
  public CollectionResult collectWorkloads() {
    return collectKinds("K8S", WORKLOAD_KINDS, null);
  }

  /**
   * Warning Events (the cluster keeps them for only about 1 hour by default, so fetch more often
   * than that).
   */
  public CollectionResult collectEvents() {
    return collectKinds("K8S_EVENT", List.of(ResourceKind.EVENT), "type=Warning");
  }

  private CollectionResult collectKinds(
      String sourcePrefix, List<ResourceKind> kinds, String fieldSelector) {
    CollectionResult result = CollectionResult.none();
    for (String site : sites()) {
      for (Cluster cluster : clusters(site)) {
        Set<String> namespaces = scope.namespacesFor(site, cluster.name(), cluster.server());
        if (namespaces.isEmpty()) {
          // Argo has not been polled yet, or no Application points at this cluster: do not produce
          // a "successful" sync run that would mislead freshness
          log.debug(
              "[{}:{}:{}] no namespaces in Argo scope, skipped",
              sourcePrefix,
              site,
              cluster.name());
          continue;
        }
        result =
            result.with(
                tracker.track(
                    "%s:%s:%s".formatted(sourcePrefix, site, cluster.name()),
                    SyncType.POLL,
                    () -> syncCluster(site, cluster, namespaces, kinds, fieldSelector)));
      }
    }
    return result;
  }

  private SyncOutcome syncCluster(
      String site,
      Cluster cluster,
      Set<String> namespaces,
      List<ResourceKind> kinds,
      String fieldSelector) {
    Instant now = clock.instant();
    AtomicInteger objects = new AtomicInteger();
    AtomicInteger newEvents = new AtomicInteger();
    List<String> errors = new ArrayList<>();
    int calls = 0;

    for (String namespace : namespaces) {
      for (ResourceKind kind : kinds) {
        calls++;
        Set<String> liveUids = new TreeSet<>();
        try {
          hub.list(
              site,
              cluster.id(),
              kind,
              namespace,
              fieldSelector,
              item -> {
                objects.incrementAndGet();
                String uid = item.path("metadata").path("uid").asString("");
                if (!uid.isEmpty()) {
                  liveUids.add(uid);
                }
                if (record(site, cluster, kind, item, now)) {
                  newEvents.incrementAndGet();
                }
              });
          // Record "the uids that exist right now" only when the whole list (all pages) succeeded;
          // a mid-way failure cannot tell which resources disappeared
          if (kind != ResourceKind.EVENT) {
            recordList(site, cluster, namespace, kind, liveUids, now);
          }
        } catch (RuntimeException e) {
          errors.add("%s/%s: %s".formatted(namespace, kind.kind(), e.getMessage()));
        }
      }
    }

    if (errors.size() == calls) {
      throw new IllegalStateException("all list calls failed: " + String.join("; ", errors));
    }
    ObjectNode cursor = JsonNodeFactory.instance.objectNode();
    cursor.put("namespaces", namespaces.size());
    cursor.put("objects", objects.get());
    cursor.put("newEvents", newEvents.get());
    cursor.put("errors", errors.size());
    return errors.isEmpty()
        ? SyncOutcome.success(objects.get(), cursor)
        : SyncOutcome.partial(objects.get(), String.join("; ", errors), cursor);
  }

  private boolean record(
      String site, Cluster cluster, ResourceKind kind, JsonNode item, Instant now) {
    JsonNode meta = item.path("metadata");
    String uid = meta.path("uid").asString("");
    String resourceVersion = meta.path("resourceVersion").asString("");
    if (uid.isEmpty() || resourceVersion.isEmpty()) {
      log.warn(
          "[{}] skip {} without uid/resourceVersion: {}",
          cluster.name(),
          kind.kind(),
          meta.path("name"));
      return false;
    }
    return recorder.record(
        new RawEvent(
            PROVIDER,
            kind.kind(),
            "k8s:%s:%s:%s".formatted(cluster.id(), uid, resourceVersion),
            "k8s:%s:%s".formatted(cluster.id(), uid),
            K8sPayloads.envelope(site, cluster, now, K8sPayloads.object(kind, item))));
  }

  /**
   * The uids of this kind that exist in this namespace right now. A list cannot see deletions, so
   * normalization uses this to mark resources that are in the database but not here with deleted_at
   * (data is never deleted, see inventory). Identical content is deduplicated within the same hour;
   * it is re-emitted every hour so omissions caused by processing order heal themselves.
   */
  private void recordList(
      String site,
      Cluster cluster,
      String namespace,
      ResourceKind kind,
      Set<String> liveUids,
      Instant now) {
    ObjectNode body = K8sPayloads.resourceList(site, cluster, namespace, kind, liveUids);
    recorder.record(
        new RawEvent(
            PROVIDER,
            "RESOURCE_LIST",
            "k8s:list:%s:%s".formatted(ContentFingerprint.of(body), HOUR_BUCKET.format(now)),
            "k8s:%s:list:%s:%s".formatted(cluster.id(), namespace, kind.kind()),
            body.put("fetchedAt", now.toString())));
  }

  private SyncOutcome syncClusters(String site) {
    List<Cluster> clusters = hub.clusters(site);
    clusterCache.put(site, clusters);
    ObjectNode body = K8sPayloads.clusters(clusters);
    recorder.record(
        new RawEvent(
            PROVIDER,
            "CLUSTER_SNAPSHOT",
            "k8s:%s:clusters:%s".formatted(site, ContentFingerprint.of(body)),
            "k8s:%s:clusters".formatted(site),
            body.put("site", site)));
    return SyncOutcome.success(clusters.size(), null);
  }

  /**
   * The cluster list is refreshed hourly; when the cache is empty (just started) fetch it once
   * immediately.
   */
  private List<Cluster> clusters(String site) {
    List<Cluster> cached = clusterCache.get(site);
    if (cached != null) {
      return cached;
    }
    try {
      List<Cluster> fetched = hub.clusters(site);
      clusterCache.put(site, fetched);
      return fetched;
    } catch (RuntimeException e) {
      log.error("[{}] cannot list clusters", site, e);
      return List.of();
    }
  }

  /** Site list (name and region). An event identical to the previous one is deduplicated. */
  public CollectionResult collectSites() {
    return CollectionResult.none().with(tracker.track("K8S_SITES", SyncType.POLL, this::syncSites));
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
            "k8s:sites:%s".formatted(ContentFingerprint.of(body)),
            "k8s:sites",
            body));
    return SyncOutcome.success(items.size(), null);
  }

  private List<String> sites() {
    try {
      return hub.sites();
    } catch (RuntimeException e) {
      log.error("cannot list sites from K8s hub", e);
      return List.of();
    }
  }
}
