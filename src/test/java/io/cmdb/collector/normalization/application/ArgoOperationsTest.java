package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.inventory.domain.deployment.ArgoOperationSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ArgoOperationsTest {

  static JsonNode app(String operationState) {
    return json(
        "{\"status\":{\"sync\":{\"status\":\"Synced\"},\"health\":{\"status\":\"Healthy\"},"
            + "\"operationState\":"
            + operationState
            + "}}");
  }

  static final String FULL =
      """
      {"phase":"Succeeded","message":"successfully synced (all tasks run)",
       "startedAt":"2026-11-14T01:00:00Z","finishedAt":"2026-11-14T01:01:00Z",
       "operation":{"initiatedBy":{"username":"release-bot","automated":false},
                    "info":[{"name":"release-id","value":"310"},{"name":"release-attempt","value":"2"}]},
       "syncResult":{"revision":"abc123"}}
      """;

  @Test
  void mapsEveryFieldOfAFinishedOperation() {
    ArgoOperationSnapshot o = ArgoOperations.of("app-1", app(FULL)).orElseThrow();

    assertThat(o.operationKey()).isEqualTo("app-1:2026-11-14T01:00:00Z");
    assertThat(o.status()).isEqualTo("SUCCEEDED");
    assertThat(o.statusMessage()).isEqualTo("successfully synced (all tasks run)");
    assertThat(o.gitRevision()).isEqualTo("abc123");
    assertThat(o.syncStatus()).isEqualTo("Synced");
    assertThat(o.healthStatus()).isEqualTo("Healthy");
    assertThat(o.startedAt()).isEqualTo(Instant.parse("2026-11-14T01:00:00Z"));
    assertThat(o.finishedAt()).isEqualTo(Instant.parse("2026-11-14T01:01:00Z"));
  }

  @Test
  void metadataKeepsThePhaseWhoStartedItAndTheOperationInfo() {
    JsonNode m = ArgoOperations.of("app-1", app(FULL)).orElseThrow().metadata();

    assertThat(m.path("phase").asString()).isEqualTo("Succeeded");
    assertThat(m.at("/initiated_by/username").asString()).isEqualTo("release-bot");
    assertThat(m.at("/initiated_by/automated").asBoolean()).isFalse();
    assertThat(m.path("argo_operation_info")).hasSize(2);
    assertThat(m.at("/argo_operation_info/0/name").asString()).isEqualTo("release-id");
    assertThat(m.at("/argo_operation_info/1/value").asString()).isEqualTo("2");
  }

  @Test
  void aRunningOperationHasNoFinishTimeOrRevisionYet() {
    ArgoOperationSnapshot o =
        ArgoOperations.of(
                "app-1", app("{\"phase\":\"Running\",\"startedAt\":\"2026-11-14T01:00:00Z\"}"))
            .orElseThrow();

    assertThat(o.status()).isEqualTo("RUNNING");
    assertThat(o.finishedAt()).isNull();
    assertThat(o.gitRevision()).isNull();
    assertThat(o.metadata().has("initiated_by")).isFalse();
    assertThat(o.metadata().has("argo_operation_info")).isFalse();
  }

  @Test
  void aMultiSourceSyncTakesTheFirstRevisionAndKeepsAll() {
    ArgoOperationSnapshot o =
        ArgoOperations.of(
                "app-1",
                app(
                    "{\"phase\":\"Succeeded\",\"startedAt\":\"2026-11-14T01:00:00Z\","
                        + "\"syncResult\":{\"revisions\":[\"r1\",\"r2\"]}}"))
            .orElseThrow();

    assertThat(o.gitRevision()).isEqualTo("r1");
    assertThat(o.metadata().path("revisions")).hasSize(2);
  }

  @Test
  void anAutomatedSyncIsRecordedAsAutomatedWithoutAUsername() {
    JsonNode m =
        ArgoOperations.of(
                "app-1",
                app(
                    "{\"phase\":\"Succeeded\",\"startedAt\":\"2026-11-14T01:00:00Z\","
                        + "\"operation\":{\"initiatedBy\":{\"automated\":true}}}"))
            .orElseThrow()
            .metadata();

    assertThat(m.at("/initiated_by/automated").asBoolean()).isTrue();
    assertThat(m.at("/initiated_by").has("username")).isFalse();
  }

  @Test
  void theArgoPhasesMapToTheDeploymentStatuses() {
    assertThat(ArgoOperations.status("Running")).isEqualTo("RUNNING");
    assertThat(ArgoOperations.status("Terminating")).isEqualTo("RUNNING");
    assertThat(ArgoOperations.status("Succeeded")).isEqualTo("SUCCEEDED");
    assertThat(ArgoOperations.status("Failed")).isEqualTo("FAILED");
    assertThat(ArgoOperations.status("Error")).isEqualTo("FAILED");
  }

  @Test
  void anUnknownOrMissingPhaseIsAnError() {
    assertThatThrownBy(() -> ArgoOperations.status("Paused"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ArgoOperations.status(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noOperationStateOrNoStartTimeMeansNoOperation() {
    assertThat(ArgoOperations.of("app-1", json("{\"status\":{}}"))).isEmpty();
    assertThat(ArgoOperations.of("app-1", json("{}"))).isEmpty();
    assertThat(ArgoOperations.of("app-1", app("{\"phase\":\"Running\"}"))).isEmpty();
  }
}
