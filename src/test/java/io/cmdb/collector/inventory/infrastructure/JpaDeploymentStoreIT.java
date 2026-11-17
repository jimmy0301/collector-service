package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationSnapshot;
import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.deployment.ArgoOperationSnapshot;
import io.cmdb.collector.inventory.domain.deployment.DeploymentStore;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.support.DatabaseTest;
import io.cmdb.collector.support.Fakes;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({JpaSiteStore.class, JpaArgoApplicationStore.class, JpaDeploymentStore.class})
class JpaDeploymentStoreIT extends DatabaseTest {

  static final Instant T1 = Instant.parse("2026-11-14T01:00:00Z");
  static final Instant T2 = Instant.parse("2026-11-14T02:00:00Z");
  static final Instant T3 = Instant.parse("2026-11-14T03:00:00Z");

  @Autowired SiteStore sites;
  @Autowired ArgoApplicationStore applications;
  @Autowired DeploymentStore deployments;

  long appId;

  @BeforeEach
  void application() {
    sites.ensureExists("S-" + run);
    appId =
        applications.upsert(
            new ArgoApplicationSnapshot(
                "S-" + run,
                "app-" + run,
                "shop",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                T1));
  }

  ArgoOperationSnapshot op(Instant startedAt, String status, String message, Instant finishedAt) {
    return new ArgoOperationSnapshot(
        "app-" + run + ":" + startedAt,
        status,
        message,
        "abc123",
        "Synced",
        "Healthy",
        startedAt,
        finishedAt,
        Fakes.json("{\"phase\":\"x\",\"initiated_by\":{\"username\":\"bot\"}}"));
  }

  Map<String, Object> row(Instant startedAt) {
    return row(
        "SELECT * FROM deployments WHERE external_operation_key=?", "app-" + run + ":" + startedAt);
  }

  String status(Instant startedAt) {
    return (String) row(startedAt).get("deployment_status");
  }

  @Test
  void anOperationBecomesADeploymentOfTheApplicationWithTheArgoFacts() {
    deployments.recordArgoOperation(appId, op(T1, "SUCCEEDED", "synced", T2));

    var r = row(T1);
    assertThat(((Number) r.get("argo_application_id")).longValue()).isEqualTo(appId);
    assertThat(r.get("trigger_type")).isEqualTo("ARGOCD");
    assertThat(r.get("deployment_status")).isEqualTo("SUCCEEDED");
    assertThat(r.get("status_message")).isEqualTo("synced");
    assertThat(r.get("git_revision")).isEqualTo("abc123");
    assertThat(r.get("sync_status")).isEqualTo("Synced");
    assertThat(r.get("health_status")).isEqualTo("Healthy");
    assertThat(
            instant(
                "SELECT started_at FROM deployments WHERE external_operation_key=?",
                "app-" + run + ":" + T1))
        .isEqualTo(T1);
    assertThat(
            instant(
                "SELECT finished_at FROM deployments WHERE external_operation_key=?",
                "app-" + run + ":" + T1))
        .isEqualTo(T2);
    assertThat(r.get("metadata").toString()).contains("bot");
    assertThat(r.get("release_deployment_id")).isNull(); // resolved later
    assertThat(r.get("build_id")).isNull();
  }

  @Test
  void theSameOperationAgainIsIdempotent() {
    deployments.recordArgoOperation(appId, op(T1, "SUCCEEDED", "synced", T2));
    deployments.recordArgoOperation(appId, op(T1, "SUCCEEDED", "synced", T2));

    assertThat(count("SELECT count(*) FROM deployments WHERE argo_application_id=?", appId))
        .isEqualTo(1);
  }

  @Test
  void aRunningDeploymentIsUpdatedUntilItFinishes() {
    deployments.recordArgoOperation(appId, op(T1, "RUNNING", null, null));
    deployments.recordArgoOperation(appId, op(T1, "RUNNING", "still going", null));
    assertThat(row(T1).get("status_message")).isEqualTo("still going");

    deployments.recordArgoOperation(appId, op(T1, "FAILED", "one or more objects failed", T2));

    assertThat(status(T1)).isEqualTo("FAILED");
    assertThat(row(T1).get("status_message")).isEqualTo("one or more objects failed");
    assertThat(count("SELECT count(*) FROM deployments WHERE argo_application_id=?", appId))
        .isEqualTo(1);
  }

  @Test
  void aFinishedDeploymentIsAFinalFactAndNeverChanges() {
    deployments.recordArgoOperation(appId, op(T1, "SUCCEEDED", "done", T2));

    deployments.recordArgoOperation(appId, op(T1, "RUNNING", "late duplicate", null));
    deployments.recordArgoOperation(appId, op(T1, "FAILED", "contradiction", T3));

    assertThat(status(T1)).isEqualTo("SUCCEEDED");
    assertThat(row(T1).get("status_message")).isEqualTo("done");
  }

  @Test
  void aNewerOperationSupersedesAnOlderOneThatIsStillRunning() {
    deployments.recordArgoOperation(appId, op(T1, "RUNNING", null, null));

    deployments.recordArgoOperation(appId, op(T2, "RUNNING", null, null));

    assertThat(status(T1)).isEqualTo("SUPERSEDED");
    assertThat(status(T2)).isEqualTo("RUNNING");
  }

  @Test
  void anOlderOperationSeenAfterANewerOneIsSupersededAtOnce() {
    deployments.recordArgoOperation(appId, op(T2, "RUNNING", null, null));

    deployments.recordArgoOperation(appId, op(T1, "RUNNING", null, null)); // arrives late

    assertThat(status(T1)).isEqualTo("SUPERSEDED");
    assertThat(status(T2)).isEqualTo("RUNNING");
  }

  @Test
  void aFinishSeenAfterSupersedingReplacesSupersededWithTheRealOutcome() {
    deployments.recordArgoOperation(appId, op(T1, "RUNNING", null, null));
    deployments.recordArgoOperation(appId, op(T2, "RUNNING", null, null));
    assertThat(status(T1)).isEqualTo("SUPERSEDED");

    deployments.recordArgoOperation(appId, op(T1, "SUCCEEDED", "done", T2));

    assertThat(status(T1)).isEqualTo("SUCCEEDED");
  }

  @Test
  void finishedDeploymentsAreNeverSuperseded() {
    deployments.recordArgoOperation(appId, op(T1, "SUCCEEDED", "done", T2));

    deployments.recordArgoOperation(appId, op(T3, "RUNNING", null, null));

    assertThat(status(T1)).isEqualTo("SUCCEEDED");
  }

  @Test
  void anotherApplicationIsNotAffected() {
    long other =
        applications.upsert(
            new ArgoApplicationSnapshot(
                "S-" + run,
                "other-" + run,
                "other",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                T1));
    deployments.recordArgoOperation(appId, op(T1, "RUNNING", null, null));

    deployments.recordArgoOperation(
        other,
        new ArgoOperationSnapshot(
            "other-" + run + ":" + T2, "RUNNING", null, null, null, null, T2, null, null));

    assertThat(status(T1)).isEqualTo("RUNNING");
  }

  @Test
  void serviceAndRuntimeTargetAreCopiedFromTheApplicationWhenItHasThem() {
    long service =
        jdbc.sql(
                "INSERT INTO services (code, name, lifecycle_status) VALUES (?, 'shop', 'DRAFT') RETURNING id")
            .param("svc-" + run)
            .query(Long.class)
            .single();
    jdbc.sql("UPDATE argo_applications SET service_id=? WHERE id=?")
        .params(service, appId)
        .update();

    deployments.recordArgoOperation(appId, op(T1, "RUNNING", null, null));

    assertThat(((Number) row(T1).get("service_id")).longValue()).isEqualTo(service);
    assertThat(row(T1).get("runtime_target_id")).isNull();
  }

  @Test
  void anOperationWithoutAnyOptionalFieldIsStored() {
    deployments.recordArgoOperation(
        appId,
        new ArgoOperationSnapshot(
            "app-" + run + ":" + T3, "RUNNING", null, null, null, null, T3, null, null));

    var r = row(T3);
    assertThat(r.get("git_revision")).isNull();
    assertThat(r.get("metadata")).isNull();
  }

  @Test
  void aRevisionAlreadyKnownIsNotBlankedByALaterEventWithoutOne() {
    deployments.recordArgoOperation(appId, op(T1, "RUNNING", null, null));

    deployments.recordArgoOperation(
        appId,
        new ArgoOperationSnapshot(
            "app-" + run + ":" + T1, "RUNNING", "m", null, null, null, T1, null, null));

    assertThat(row(T1).get("git_revision")).isEqualTo("abc123");
  }
}
