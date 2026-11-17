package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.release.ReleaseDeploymentSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseStore;
import io.cmdb.collector.support.DatabaseTest;
import io.cmdb.collector.support.Fakes;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(JpaReleaseStore.class)
class JpaReleaseStoreIT extends DatabaseTest {

  static final Instant T1 = Instant.parse("2026-11-14T01:00:00Z");
  static final Instant T2 = Instant.parse("2026-11-14T02:00:00Z");
  static final Instant T3 = Instant.parse("2026-11-14T03:00:00Z");

  @Autowired ReleaseStore releases;

  String project() {
    return "P-" + run;
  }

  ReleaseSnapshot release(String name, String status, Instant modifiedAt) {
    return new ReleaseSnapshot(
        project(),
        "310",
        "7-" + run,
        "shop-cd",
        name,
        status,
        "manual",
        "_ci",
        "5521",
        "refs/heads/main",
        Fakes.json("[{\"alias\":\"_ci\"}]"),
        "Alice",
        T1,
        modifiedAt);
  }

  ReleaseDeploymentSnapshot deployment(
      long releaseId, int attempt, String status, String defEnv, Instant modifiedAt) {
    return new ReleaseDeploymentSnapshot(
        releaseId,
        "env-2001",
        defEnv,
        "uat",
        attempt,
        "9001",
        status,
        "PhaseSucceeded",
        "manual",
        "Alice",
        T1,
        T1,
        null,
        modifiedAt);
  }

  java.util.Map<String, Object> releaseRow() {
    return row("SELECT * FROM releases WHERE project=? AND external_release_id='310'", project());
  }

  // ---------- releases ----------

  @Test
  void upsertInsertsAReleaseAndTheSameReleaseAgainUpdatesTheSameRow() {
    long id = releases.upsert(release("Release-310", "active", T2));
    long again = releases.upsert(release("Release-310 renamed", "abandoned", T3));

    assertThat(again).isEqualTo(id);
    var r = releaseRow();
    assertThat(r.get("name")).isEqualTo("Release-310 renamed");
    assertThat(r.get("status")).isEqualTo("abandoned");
    assertThat(r.get("artifact_build_ref")).isEqualTo("5521");
    assertThat(r.get("artifacts").toString()).contains("_ci");
    assertThat(instant("SELECT modified_at FROM releases WHERE id=?", id)).isEqualTo(T3);
    assertThat(count("SELECT count(*) FROM releases WHERE project=?", project())).isEqualTo(1);
  }

  @Test
  void anOlderObservationNeverOverwritesANewerOneButStillReturnsTheId() {
    long id = releases.upsert(release("new", "active", T3));

    long old = releases.upsert(release("old", "draft", T2));

    assertThat(old).isEqualTo(id);
    assertThat(releaseRow().get("name")).isEqualTo("new");
    assertThat(releaseRow().get("status")).isEqualTo("active");
  }

  @Test
  void aNullNeverBlanksAStoredValue() {
    releases.upsert(release("Release-310", "active", T2));

    releases.upsert(
        new ReleaseSnapshot(
            project(),
            "310",
            "7-" + run,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            T3));

    var r = releaseRow();
    assertThat(r.get("name")).isEqualTo("Release-310");
    assertThat(r.get("definition_name")).isEqualTo("shop-cd");
    assertThat(r.get("artifact_alias")).isEqualTo("_ci");
    assertThat(r.get("created_by")).isEqualTo("Alice");
  }

  @Test
  void aStubIsCreatedOnceAndOverwrittenByTheRealRelease() {
    long stub = releases.ensureStub(project(), "310", "7-" + run, "shop-cd", "Release-310");
    long again = releases.ensureStub(project(), "310", "7-" + run, "other", "other");
    assertThat(again).isEqualTo(stub);
    assertThat(releaseRow().get("definition_name")).isEqualTo("shop-cd"); // never overwritten
    assertThat(releaseRow().get("status")).isNull();

    // the release arrives with an observation older than anything else: the stub has no
    // modified_at, so it is still filled in
    long real = releases.upsert(release("Release-310", "active", T1));

    assertThat(real).isEqualTo(stub);
    assertThat(releaseRow().get("status")).isEqualTo("active");
  }

  @Test
  void theSameReleaseIdInAnotherProjectIsAnotherRelease() {
    long a = releases.upsert(release("a", "active", T1));
    long b =
        releases.upsert(
            new ReleaseSnapshot(
                "other-" + run,
                "310",
                "7",
                null,
                "b",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                T1));

    assertThat(b).isNotEqualTo(a);
  }

  @Test
  void pipelineIdIsResolvedFromARegisteredReleaseDefinitionAndKeptAfterwards() {
    long serviceId = serviceId();
    jdbc.sql(
            "INSERT INTO pipelines (service_id, provider, pipeline_type, external_id, name)"
                + " VALUES (?, 'AZURE_DEVOPS_RELEASE', 'CD', ?, 'shop-cd')")
        .params(serviceId, "7-" + run)
        .update();

    long id = releases.upsert(release("Release-310", "active", T2));

    assertThat(
            count(
                "SELECT count(*) FROM releases r JOIN pipelines p ON p.id=r.pipeline_id WHERE r.id=?",
                id))
        .isEqualTo(1);
  }

  @Test
  void pipelineIdStaysNullWhenTheDefinitionIsNotRegistered() {
    releases.upsert(release("Release-310", "active", T2));

    assertThat(releaseRow().get("pipeline_id")).isNull();
  }

  long serviceId() {
    return jdbc.sql(
            "INSERT INTO services (code, name, lifecycle_status) VALUES (?, 'shop', 'DRAFT') RETURNING id")
        .param("svc-" + java.util.UUID.randomUUID())
        .query(Long.class)
        .single();
  }

  // ---------- release deployments ----------

  java.util.Map<String, Object> deploymentRow(long releaseId, int attempt) {
    return row(
        "SELECT * FROM release_deployments WHERE release_id=? AND external_release_env_id='env-2001' AND attempt=?",
        releaseId,
        attempt);
  }

  @Test
  void aDeploymentIsKeyedByReleaseStageInstanceAndAttempt() {
    long id = releases.ensureStub(project(), "310", "7-" + run, "shop-cd", "Release-310");

    releases.upsertDeployment(deployment(id, 1, "failed", "55", T1));
    releases.upsertDeployment(deployment(id, 2, "succeeded", "55", T2)); // a retry

    assertThat(count("SELECT count(*) FROM release_deployments WHERE release_id=?", id))
        .isEqualTo(2);
    assertThat(deploymentRow(id, 1).get("deployment_status")).isEqualTo("failed");
    assertThat(deploymentRow(id, 2).get("deployment_status")).isEqualTo("succeeded");
  }

  @Test
  void aDeploymentIsUpdatedInPlaceAndAnOlderObservationIsIgnored() {
    long id = releases.ensureStub(project(), "310", "7-" + run, "shop-cd", "Release-310");
    releases.upsertDeployment(deployment(id, 1, "inProgress", "55", T1));

    releases.upsertDeployment(deployment(id, 1, "succeeded", "55", T3));
    releases.upsertDeployment(deployment(id, 1, "inProgress", "55", T2)); // arrives late

    assertThat(count("SELECT count(*) FROM release_deployments WHERE release_id=?", id))
        .isEqualTo(1);
    assertThat(deploymentRow(id, 1).get("deployment_status")).isEqualTo("succeeded");
    assertThat(instant("SELECT last_modified_at FROM release_deployments WHERE release_id=?", id))
        .isEqualTo(T3);
  }

  @Test
  void runtimeTargetIsResolvedOnlyWhenExactlyOneIsRegisteredForTheStage() {
    long id = releases.ensureStub(project(), "310", "7-" + run, "shop-cd", "Release-310");
    String stage = "stage-" + run;

    releases.upsertDeployment(deployment(id, 1, "succeeded", stage, T1));
    assertThat(deploymentRow(id, 1).get("runtime_target_id")).isNull(); // none registered

    long target = runtimeTarget("a", stage);
    releases.upsertDeployment(deployment(id, 1, "succeeded", stage, T2));
    assertThat(((Number) deploymentRow(id, 1).get("runtime_target_id")).longValue())
        .isEqualTo(target);
  }

  @Test
  void anAmbiguousStageDoesNotGuessARuntimeTarget() {
    long id = releases.ensureStub(project(), "310", "7-" + run, "shop-cd", "Release-310");
    String stage = "stage-" + run;
    runtimeTarget("a", stage);
    runtimeTarget("b", stage);

    releases.upsertDeployment(deployment(id, 1, "succeeded", stage, T1));

    assertThat(deploymentRow(id, 1).get("runtime_target_id")).isNull();
  }

  long runtimeTarget(String workload, String stage) {
    long service = serviceId();
    long site =
        jdbc.sql("INSERT INTO sites (code, name) VALUES (?, 'x') RETURNING id")
            .param("S-" + run + workload)
            .query(Long.class)
            .single();
    long cluster =
        jdbc.sql(
                "INSERT INTO clusters (site_id, code, name, external_id) VALUES (?, ?, 'c', ?) RETURNING id")
            .params(site, "c-" + run + workload, "cid-" + run + workload)
            .query(Long.class)
            .single();
    return jdbc.sql(
            "INSERT INTO runtime_targets (service_id, cluster_id, namespace, environment, workload_name, release_definition_env_id)"
                + " VALUES (?, ?, 'shop', 'uat', ?, ?) RETURNING id")
        .params(service, cluster, workload, stage)
        .query(Long.class)
        .single();
  }
}
