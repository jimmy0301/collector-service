package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.inventory.domain.release.ReleaseDeploymentSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseSnapshot;
import io.cmdb.collector.normalization.application.RecordingStores.ReleaseStub;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdoHandlersTest {

  final RecordingStores.Releases releases = new RecordingStores.Releases();
  final AdoReleaseHandler releaseHandler = new AdoReleaseHandler(releases);
  final AdoReleaseDeploymentHandler deploymentHandler = new AdoReleaseDeploymentHandler(releases);

  static RawEvent event(String type, String resource) {
    return new RawEvent(
        "ADO",
        type,
        "e",
        "k",
        json(
            "{\"project\":\"Shop\",\"fetchedAt\":\"2026-11-14T03:00:00Z\",\"resource\":"
                + resource
                + "}"));
  }

  static final String RELEASE =
      """
      {"id":310,"name":"Release-310","status":"active","reason":"continuousIntegration",
       "createdOn":"2026-11-14T00:55:00.000Z","modifiedOn":"2026-11-14T01:05:00.000Z",
       "createdBy":{"displayName":"Alice Chen"},
       "releaseDefinition":{"id":7,"name":"shop-cd"},
       "artifacts":[
         {"alias":"_other","type":"Build","isPrimary":false,
          "definitionReference":{"version":{"id":"1"},"branch":{"id":"refs/heads/dev"}}},
         {"alias":"_shop-ci","type":"Build","isPrimary":true,
          "definitionReference":{"version":{"id":"5521"},"branch":{"id":"refs/heads/main"}}}]}
      """;

  static final String DEPLOYMENT =
      """
      {"id":9001,"release":{"id":310,"name":"Release-310"},
       "releaseDefinition":{"id":7,"name":"shop-cd"},
       "releaseEnvironment":{"id":2001,"name":"uat"},"definitionEnvironmentId":55,"attempt":2,
       "reason":"manual","deploymentStatus":"succeeded","operationStatus":"PhaseSucceeded",
       "requestedBy":{"displayName":"Alice Chen"},
       "queuedOn":"2026-11-14T01:00:00.000Z","startedOn":"2026-11-14T01:01:00.000Z",
       "completedOn":"2026-11-14T01:05:00.000Z","lastModifiedOn":"2026-11-14T01:05:00.000Z"}
      """;

  @Test
  void supportsOnlyTheirOwnEventTypesFromAdo() {
    assertThat(releaseHandler.supports("ADO", "RELEASE")).isTrue();
    assertThat(releaseHandler.supports("ADO", "RELEASE_DEPLOYMENT")).isFalse();
    assertThat(releaseHandler.supports("ARGO", "RELEASE")).isFalse();
    assertThat(deploymentHandler.supports("ADO", "RELEASE_DEPLOYMENT")).isTrue();
    assertThat(deploymentHandler.supports("ADO", "RELEASE")).isFalse();
  }

  @Test
  void aReleaseMapsEveryFieldAndTakesTheArtifactFromThePrimaryBuild() {
    releaseHandler.handle(event("RELEASE", RELEASE));

    ReleaseSnapshot r = releases.upserts.get(0);
    assertThat(r.project()).isEqualTo("Shop");
    assertThat(r.externalReleaseId()).isEqualTo("310");
    assertThat(r.definitionId()).isEqualTo("7");
    assertThat(r.definitionName()).isEqualTo("shop-cd");
    assertThat(r.name()).isEqualTo("Release-310");
    assertThat(r.status()).isEqualTo("active");
    assertThat(r.reason()).isEqualTo("continuousIntegration");
    assertThat(r.artifactAlias()).isEqualTo("_shop-ci");
    assertThat(r.artifactBuildRef()).isEqualTo("5521");
    assertThat(r.sourceBranch()).isEqualTo("refs/heads/main");
    assertThat(r.artifacts()).hasSize(2);
    assertThat(r.createdBy()).isEqualTo("Alice Chen");
    assertThat(r.createdAt()).isEqualTo(Instant.parse("2026-11-14T00:55:00Z"));
    assertThat(r.modifiedAt()).isEqualTo(Instant.parse("2026-11-14T01:05:00Z"));
  }

  @Test
  void withoutAPrimaryFlagTheFirstBuildArtifactIsUsedAndOtherTypesAreIgnored() {
    releaseHandler.handle(
        event(
            "RELEASE",
            """
            {"id":1,"releaseDefinition":{"id":7},"artifacts":[
              {"alias":"pkg","type":"Package","definitionReference":{"version":{"id":"9"}}},
              {"alias":"ci","type":"Build","definitionReference":{"version":{"id":"42"}}}]}
            """));

    assertThat(releases.upserts.get(0).artifactAlias()).isEqualTo("ci");
    assertThat(releases.upserts.get(0).artifactBuildRef()).isEqualTo("42");
  }

  @Test
  void aReleaseWithoutBuildArtifactsHasNoBuildReference() {
    releaseHandler.handle(event("RELEASE", "{\"id\":1,\"releaseDefinition\":{\"id\":7}}"));

    ReleaseSnapshot r = releases.upserts.get(0);
    assertThat(r.artifactAlias()).isNull();
    assertThat(r.artifactBuildRef()).isNull();
    assertThat(r.modifiedAt()).isNull();
  }

  @Test
  void aReleaseWithoutADefinitionFailsTheEvent() {
    assertThatThrownBy(() -> releaseHandler.handle(event("RELEASE", "{\"id\":1}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  @Test
  void aDeploymentEnsuresItsReleaseFirstAndThenStoresTheDeployment() {
    deploymentHandler.handle(event("RELEASE_DEPLOYMENT", DEPLOYMENT));

    assertThat(releases.stubs)
        .containsExactly(new ReleaseStub("Shop", "310", "7", "shop-cd", "Release-310"));
    ReleaseDeploymentSnapshot d = releases.deployments.get(0);
    assertThat(d.releaseId()).isEqualTo(7); // the id the store returned for the release
    assertThat(d.externalReleaseEnvId()).isEqualTo("2001");
    assertThat(d.definitionEnvironmentId()).isEqualTo("55");
    assertThat(d.environmentName()).isEqualTo("uat");
    assertThat(d.attempt()).isEqualTo(2);
    assertThat(d.externalDeploymentId()).isEqualTo("9001");
    assertThat(d.deploymentStatus()).isEqualTo("succeeded");
    assertThat(d.operationStatus()).isEqualTo("PhaseSucceeded");
    assertThat(d.reason()).isEqualTo("manual");
    assertThat(d.requestedBy()).isEqualTo("Alice Chen");
    assertThat(d.queuedAt()).isEqualTo(Instant.parse("2026-11-14T01:00:00Z"));
    assertThat(d.startedAt()).isEqualTo(Instant.parse("2026-11-14T01:01:00Z"));
    assertThat(d.completedAt()).isEqualTo(Instant.parse("2026-11-14T01:05:00Z"));
    assertThat(d.lastModifiedAt()).isEqualTo(Instant.parse("2026-11-14T01:05:00Z"));
  }

  @Test
  void aRunningDeploymentHasNoCompletionTimeAndAttemptDefaultsToOne() {
    JsonNode running =
        json(
            """
            {"id":2,"release":{"id":3},"releaseDefinition":{"id":7},
             "releaseEnvironment":{"id":9,"name":"prod"},"deploymentStatus":"inProgress"}
            """);
    deploymentHandler.handle(event("RELEASE_DEPLOYMENT", running.toString()));

    ReleaseDeploymentSnapshot d = releases.deployments.get(0);
    assertThat(d.completedAt()).isNull();
    assertThat(d.lastModifiedAt()).isNull();
    assertThat(d.attempt()).isEqualTo(1);
  }

  @Test
  void aDeploymentWithoutAStageFailsTheEvent() {
    assertThatThrownBy(
            () ->
                deploymentHandler.handle(
                    event(
                        "RELEASE_DEPLOYMENT",
                        "{\"id\":2,\"release\":{\"id\":3},\"releaseDefinition\":{\"id\":7}}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(releases.deployments).isEmpty();
  }
}
