package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationSnapshot;
import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.deployment.DeploymentStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * ARGO APPLICATION_SNAPSHOT ({site, fetchedAt, resource: Application}) → argo_applications, and its
 * latest sync operation (status.operationState) → deployments.
 *
 * <p>service_id and runtime_target_id are not filled here (they are registered by hand). Only the
 * fields of each source that identify it are kept (repoURL, path, targetRevision, chart, ref,
 * name): the rest of a source, such as helm values, is not needed.
 */
@Component
class ArgoApplicationHandler implements RawEventHandler {

  private static final String[] SOURCE_FIELDS = {
    "repoURL", "path", "targetRevision", "chart", "ref", "name"
  };

  private final ArgoApplicationStore applications;
  private final DeploymentStore deployments;

  ArgoApplicationHandler(ArgoApplicationStore applications, DeploymentStore deployments) {
    this.applications = applications;
    this.deployments = deployments;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "ARGO".equals(provider) && "APPLICATION_SNAPSHOT".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    JsonNode p = event.payload();
    JsonNode app = p.path("resource");
    JsonNode spec = app.path("spec");
    JsonNode destination = spec.path("destination");
    JsonNode sync = app.path("status").path("sync");

    long applicationId =
        applications.upsert(
            new ArgoApplicationSnapshot(
                Payloads.required(p, "site"),
                Payloads.required(app.path("metadata"), "uid"),
                Payloads.required(app.path("metadata"), "name"),
                spec.path("project").asString(null),
                destination.path("server").asString(null),
                destination.path("name").asString(null),
                destination.path("namespace").asString(null),
                sources(spec),
                sync.path("status").asString(null),
                app.path("status").path("health").path("status").asString(null),
                // automated sync is on when spec.syncPolicy.automated is present, even if it is {}
                spec.path("syncPolicy").path("automated").isObject(),
                revisions(sync),
                Payloads.instant(p, "fetchedAt")));

    // The latest sync operation is the Argo-side fact of a deployment
    ArgoOperations.of(app.path("metadata").path("uid").asString(), app)
        .ifPresent(operation -> deployments.recordArgoOperation(applicationId, operation));
  }

  /** spec.sources[], or the single spec.source as a one-element array. */
  private static JsonNode sources(JsonNode spec) {
    var result = JsonNodeFactory.instance.arrayNode();
    if (spec.path("sources").isArray()) {
      spec.path("sources").forEach(s -> result.add(source(s)));
    } else if (spec.path("source").isObject()) {
      result.add(source(spec.path("source")));
    }
    return result;
  }

  private static JsonNode source(JsonNode s) {
    var out = JsonNodeFactory.instance.objectNode();
    for (String field : SOURCE_FIELDS) {
      if (s.has(field) && !s.get(field).isNull()) {
        out.set(field, s.get(field));
      }
    }
    return out;
  }

  /** status.sync.revisions[], or the single status.sync.revision as a one-element array. */
  private static JsonNode revisions(JsonNode sync) {
    if (sync.path("revisions").isArray()) {
      return sync.path("revisions");
    }
    String revision = sync.path("revision").asString("");
    if (revision.isBlank()) {
      return null;
    }
    return JsonNodeFactory.instance.arrayNode().add(revision);
  }
}
