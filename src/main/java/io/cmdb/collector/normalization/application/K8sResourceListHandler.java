package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * K8S RESOURCE_LIST ({site, cluster:{id,name}, namespace, kind, uids, fetchedAt}): the uids that
 * existed for one kind in one namespace when a complete list succeeded at fetchedAt. Resources that
 * are in the database but not in it get deleted_at (data is kept forever, only marked).
 */
@Component
class K8sResourceListHandler implements RawEventHandler {

  private final K8sResourceStore resources;

  K8sResourceListHandler(K8sResourceStore resources) {
    this.resources = resources;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "K8S".equals(provider) && "RESOURCE_LIST".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    JsonNode p = event.payload();
    List<String> uids = new ArrayList<>();
    p.path("uids").forEach(u -> uids.add(u.asString("")));
    resources.markMissingDeleted(
        Payloads.required(p.path("cluster"), "id"),
        Payloads.required(p, "namespace"),
        Payloads.required(p, "kind"),
        uids,
        Payloads.instant(p, "fetchedAt"));
  }
}
