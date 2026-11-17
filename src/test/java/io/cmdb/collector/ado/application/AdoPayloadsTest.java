package io.cmdb.collector.ado.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.support.Fakes;
import io.cmdb.collector.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdoPayloadsTest {

  JsonNode deployment() {
    return Fakes.json(Fixtures.load("ado/deployments.json")).path("value").get(0);
  }

  JsonNode release() {
    return Fakes.json(Fixtures.load("ado/release-310.json"));
  }

  @Test
  void deploymentKeepsOnlyTheWhitelistedFields() {
    JsonNode d = AdoPayloads.deployment(deployment());

    assertThat(d.path("id").asInt()).isEqualTo(9001);
    assertThat(d.path("release").path("id").asInt()).isEqualTo(310);
    assertThat(d.path("releaseEnvironment").path("name").asString()).isEqualTo("uat");
    assertThat(d.path("definitionEnvironmentId").asInt()).isEqualTo(55);
    assertThat(d.path("attempt").asInt()).isEqualTo(1);
    assertThat(d.path("lastModifiedOn").asString()).isEqualTo("2026-11-14T01:05:00.000Z");
    // only the display name of a person is kept, not the e-mail address or the id
    assertThat(d.path("requestedBy").propertyNames()).containsExactly("displayName");
    // approvals are not part of a deployment row
    assertThat(d.has("preDeployApprovals")).isFalse();
    assertThat(d.path("release").has("url")).isFalse();
  }

  @Test
  void releaseDropsVariablesVariableGroupsAndEnvironmentDefinitions() {
    JsonNode r = AdoPayloads.release(release());

    assertThat(r.toString()).doesNotContain("hunter2", "secrets", "Token", "alice@example.com");
    assertThat(r.has("variables")).isFalse();
    assertThat(r.has("variableGroups")).isFalse();
    assertThat(r.has("environments")).isFalse();
    assertThat(r.path("createdBy").propertyNames()).containsExactly("displayName");
  }

  @Test
  void releaseKeepsTheArtifactReferenceNeededToFindTheCiBuild() {
    JsonNode artifact = AdoPayloads.release(release()).path("artifacts").get(0);

    assertThat(artifact.path("alias").asString()).isEqualTo("_shop-ci");
    assertThat(artifact.path("type").asString()).isEqualTo("Build");
    assertThat(artifact.path("isPrimary").asBoolean()).isTrue();
    assertThat(artifact.at("/definitionReference/version/id").asString()).isEqualTo("5521");
    assertThat(artifact.at("/definitionReference/branch/id").asString())
        .isEqualTo("refs/heads/main");
    assertThat(artifact.at("/definitionReference/definition/name").asString()).isEqualTo("shop-ci");
    assertThat(artifact.at("/definitionReference").has("repository")).isFalse();
  }

  @Test
  void aMissingOptionalFieldIsSimplyAbsent() {
    JsonNode d = AdoPayloads.deployment(Fakes.json("{\"id\":1}"));

    assertThat(d.propertyNames()).containsExactly("id");
  }

  @Test
  void theEnvelopeHasProjectFetchedAtAndResource() {
    var envelope =
        AdoPayloads.envelope(
            "Shop", Instant.parse("2026-11-14T03:00:00Z"), AdoPayloads.deployment(deployment()));

    assertThat(envelope.path("project").asString()).isEqualTo("Shop");
    assertThat(envelope.path("fetchedAt").asString()).isEqualTo("2026-11-14T03:00:00Z");
    assertThat(envelope.path("resource").path("id").asInt()).isEqualTo(9001);
  }
}
