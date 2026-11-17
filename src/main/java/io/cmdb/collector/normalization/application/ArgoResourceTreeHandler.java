package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.argo.ArgoResourceNode;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * ARGO RESOURCE_TREE_SNAPSHOT ({site, fetchedAt, application:{uid,name}, resource:{nodes,
 * orphanedNodes}}) → k8s_resources.argo_application_id and argo_health_status.
 *
 * <p>Only {@code nodes[]} (the resources Argo manages) are linked; {@code orphanedNodes[]} are in
 * the Application's scope but not managed by it. The Application may not have been processed yet,
 * so a stub is created first and the APPLICATION_SNAPSHOT event overwrites it later. Resources that
 * K8s has not reported yet are skipped; the tree is landed again every hour, which links them
 * later.
 */
@Component
class ArgoResourceTreeHandler implements RawEventHandler {

  private final ArgoApplicationStore applications;

  ArgoResourceTreeHandler(ArgoApplicationStore applications) {
    this.applications = applications;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "ARGO".equals(provider) && "RESOURCE_TREE_SNAPSHOT".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    JsonNode p = event.payload();
    JsonNode app = p.path("application");
    long applicationId =
        applications.ensureStub(
            Payloads.required(p, "site"),
            Payloads.required(app, "uid"),
            Payloads.required(app, "name"));

    List<ArgoResourceNode> nodes = new ArrayList<>();
    for (JsonNode n : p.path("resource").path("nodes")) {
      String uid = n.path("uid").asString("");
      if (!uid.isBlank()) {
        nodes.add(
            new ArgoResourceNode(
                uid,
                n.path("kind").asString(null),
                n.path("namespace").asString(null),
                n.path("name").asString(null),
                n.path("health").path("status").asString(null)));
      }
    }
    applications.linkResources(applicationId, nodes);
  }
}
