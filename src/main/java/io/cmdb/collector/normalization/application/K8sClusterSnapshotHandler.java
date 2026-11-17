package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** K8S CLUSTER_SNAPSHOT ({items:[{id,name,server}], site}) → clusters. */
@Component
class K8sClusterSnapshotHandler implements RawEventHandler {

  private final SiteStore sites;
  private final ClusterStore clusters;
  private final ArgoApplicationStore applications;

  K8sClusterSnapshotHandler(
      SiteStore sites, ClusterStore clusters, ArgoApplicationStore applications) {
    this.sites = sites;
    this.clusters = clusters;
    this.applications = applications;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "K8S".equals(provider) && "CLUSTER_SNAPSHOT".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    String site = Payloads.required(event.payload(), "site");
    sites.ensureExists(site);
    for (JsonNode item : event.payload().path("items")) {
      clusters.upsert(
          new ClusterSnapshot(
              site,
              Payloads.required(item, "id"),
              Payloads.required(item, "name"),
              item.path("server").asString(null)));
    }
    // Applications seen before their cluster was registered get their cluster now
    applications.linkUnmappedClusters(site);
  }
}
