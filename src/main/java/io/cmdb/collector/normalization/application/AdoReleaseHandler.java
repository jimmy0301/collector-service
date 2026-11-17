package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.release.ReleaseSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * ADO RELEASE ({project, fetchedAt, resource: release}) → releases. The CI build the release was
 * created from is kept as the raw build reference (artifact_build_ref); resolving it to builds.id
 * waits for the CI collection.
 */
@Component
class AdoReleaseHandler implements RawEventHandler {

  private final ReleaseStore releases;

  AdoReleaseHandler(ReleaseStore releases) {
    this.releases = releases;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "ADO".equals(provider) && "RELEASE".equals(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    JsonNode p = event.payload();
    JsonNode r = p.path("resource");
    JsonNode primary = primaryBuildArtifact(r.path("artifacts"));
    JsonNode ref = primary.path("definitionReference");

    releases.upsert(
        new ReleaseSnapshot(
            Payloads.required(p, "project"),
            Payloads.required(r, "id"),
            Payloads.required(r.path("releaseDefinition"), "id"),
            r.path("releaseDefinition").path("name").asString(null),
            r.path("name").asString(null),
            r.path("status").asString(null),
            r.path("reason").asString(null),
            primary.path("alias").asString(null),
            ref.path("version").path("id").asString(null),
            ref.path("branch").path("id").asString(null),
            r.get("artifacts"),
            r.path("createdBy").path("displayName").asString(null),
            Payloads.optionalInstant(r.path("createdOn")),
            Payloads.optionalInstant(r.path("modifiedOn"))));
  }

  /** The primary Build artifact, else the first Build artifact, else nothing (a missing node). */
  private static JsonNode primaryBuildArtifact(JsonNode artifacts) {
    JsonNode firstBuild = null;
    for (JsonNode a : artifacts) {
      if (!"Build".equals(a.path("type").asString(""))) {
        continue;
      }
      if (a.path("isPrimary").asBoolean(false)) {
        return a;
      }
      if (firstBuild == null) {
        firstBuild = a;
      }
    }
    return firstBuild == null ? artifacts.path("none") : firstBuild;
  }
}
