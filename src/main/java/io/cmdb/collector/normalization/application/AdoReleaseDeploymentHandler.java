package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.release.ReleaseDeploymentSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * ADO RELEASE_DEPLOYMENT ({project, fetchedAt, resource: deployment}) → release_deployments: one
 * row per (release, stage instance, attempt). The release may not have been processed yet, so a
 * stub is created first and the RELEASE event overwrites it later.
 */
@Component
class AdoReleaseDeploymentHandler implements RawEventHandler {

  private final ReleaseStore releases;

  AdoReleaseDeploymentHandler(ReleaseStore releases) {
    this.releases = releases;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "ADO".equals(provider) && "RELEASE_DEPLOYMENT".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    JsonNode p = event.payload();
    JsonNode d = p.path("resource");
    JsonNode release = d.path("release");
    JsonNode definition = d.path("releaseDefinition");
    JsonNode env = d.path("releaseEnvironment");

    long releaseId =
        releases.ensureStub(
            Payloads.required(p, "project"),
            Payloads.required(release, "id"),
            Payloads.required(definition, "id"),
            definition.path("name").asString(null),
            release.path("name").asString(null));

    releases.upsertDeployment(
        new ReleaseDeploymentSnapshot(
            releaseId,
            Payloads.required(env, "id"),
            d.path("definitionEnvironmentId").asString(null),
            Payloads.required(env, "name"),
            d.path("attempt").asInt(1),
            d.path("id").asString(null),
            d.path("deploymentStatus").asString(null),
            d.path("operationStatus").asString(null),
            d.path("reason").asString(null),
            d.path("requestedBy").path("displayName").asString(null),
            Payloads.optionalInstant(d.path("queuedOn")),
            Payloads.optionalInstant(d.path("startedOn")),
            Payloads.optionalInstant(d.path("completedOn")),
            Payloads.optionalInstant(d.path("lastModifiedOn"))));
  }
}
