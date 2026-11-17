package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.k8s.K8sEventSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sEventStore;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * K8S Event ({site, cluster:{id,name}, fetchedAt, object}) → k8s_events. Only Warning Events are
 * collected. One row per event uid; Kubernetes updates an Event in place (count, lastTimestamp), so
 * later versions update the same row. The cluster keeps Events for only about 1 hour, which is why
 * they are landed here and never deleted.
 */
@Component
class K8sEventHandler implements RawEventHandler {

  private final SiteStore sites;
  private final ClusterStore clusters;
  private final K8sEventStore events;

  K8sEventHandler(SiteStore sites, ClusterStore clusters, K8sEventStore events) {
    this.sites = sites;
    this.clusters = clusters;
    this.events = events;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "K8S".equals(provider) && "Event".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    JsonNode p = event.payload();
    String site = Payloads.required(p, "site");
    String clusterId = Payloads.required(p.path("cluster"), "id");
    String clusterName = Payloads.required(p.path("cluster"), "name");
    Instant observedAt = Payloads.instant(p, "fetchedAt");
    JsonNode object = p.path("object");
    JsonNode meta = object.path("metadata");
    JsonNode involved = object.path("involvedObject");

    // The cluster list event may not have been processed yet: make sure the site and cluster exist
    sites.ensureExists(site);
    clusters.ensureExists(new ClusterSnapshot(site, clusterId, clusterName, null));

    // core/v1 Events carry firstTimestamp / lastTimestamp; newer ones may only have eventTime and
    // series
    Instant firstSeen =
        firstNonNull(
            Payloads.optionalInstant(object.path("firstTimestamp")),
            Payloads.optionalInstant(object.path("eventTime")),
            Payloads.optionalInstant(meta.path("creationTimestamp")));
    Instant lastSeen =
        firstNonNull(
            Payloads.optionalInstant(object.path("lastTimestamp")),
            Payloads.optionalInstant(object.path("series").path("lastObservedTime")),
            Payloads.optionalInstant(object.path("eventTime")),
            firstSeen);
    int count =
        Math.max(
            1,
            Math.max(object.path("count").asInt(0), object.path("series").path("count").asInt(0)));

    events.upsert(
        new K8sEventSnapshot(
            clusterId,
            Payloads.required(meta, "uid"),
            meta.path("namespace").asString(null),
            involved.path("kind").asString(null),
            involved.path("name").asString(null),
            involved.path("uid").asString(null),
            object.path("type").asString(null),
            object.path("reason").asString(null),
            object.path("message").asString(null),
            count,
            firstSeen,
            lastSeen,
            observedAt));
  }

  private static Instant firstNonNull(Instant... values) {
    for (Instant v : values) {
      if (v != null) {
        return v;
      }
    }
    return null;
  }
}
