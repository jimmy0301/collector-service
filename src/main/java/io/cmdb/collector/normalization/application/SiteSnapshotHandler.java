package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.site.SiteSnapshot;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** SITE_SNAPSHOT (reported separately by the Argo hub and the K8s integration service) → sites. */
@Component
class SiteSnapshotHandler implements RawEventHandler {

  private final SiteStore sites;

  SiteSnapshotHandler(SiteStore sites) {
    this.sites = sites;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return ("ARGO".equals(provider) || "K8S".equals(provider)) && "SITE_SNAPSHOT".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    for (JsonNode item : event.payload().path("items")) {
      sites.upsert(
          new SiteSnapshot(
              Payloads.required(item, "code"),
              item.path("name").asString(null),
              item.path("region").asString(null)));
    }
  }
}
