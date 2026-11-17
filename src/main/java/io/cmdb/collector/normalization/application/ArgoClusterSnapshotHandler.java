package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * ARGO CLUSTER_SNAPSHOT ({site, fetchedAt, resource:{items:[{name,server}]}}) →
 * clusters.argo_cluster_name.
 *
 * <p>Matches the clusters reported by the K8s integration service by server. No match is not a
 * failure (for example Argo's in-cluster is outside the K8s integration service's scope): the
 * collector regenerates this event every hour, so a cluster created later is picked up in the next
 * round.
 */
@Component
class ArgoClusterSnapshotHandler implements RawEventHandler {

  private static final Logger log = LoggerFactory.getLogger(ArgoClusterSnapshotHandler.class);

  private final ClusterStore clusters;
  private final ArgoApplicationStore applications;

  ArgoClusterSnapshotHandler(ClusterStore clusters, ArgoApplicationStore applications) {
    this.clusters = clusters;
    this.applications = applications;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "ARGO".equals(provider) && "CLUSTER_SNAPSHOT".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    String site = Payloads.required(event.payload(), "site");
    for (JsonNode item : event.payload().path("resource").path("items")) {
      String server = item.path("server").asString("");
      String name = item.path("name").asString("");
      if (server.isBlank() || name.isBlank()) {
        continue;
      }
      int updated = clusters.setArgoClusterName(site, server, name);
      if (updated == 0) {
        log.debug(
            "[{}] Argo cluster {} ({}) not matched or already up to date", site, name, server);
      }
    }
    // Applications seen before their cluster had an Argo name get their cluster now
    applications.linkUnmappedClusters(site);
  }
}
