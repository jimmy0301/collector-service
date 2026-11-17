package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceHistoryStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceState;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.inventory.domain.k8s.PodContainerSnapshot;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.support.DatabaseTest;
import io.cmdb.collector.support.Fakes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({
  JpaSiteStore.class,
  JpaClusterStore.class,
  JpaK8sResourceStore.class,
  JpaK8sResourceHistoryStore.class
})
class JpaK8sResourceStoreIT extends DatabaseTest {

  static final Instant T1 = Instant.parse("2026-11-14T00:00:10Z");
  static final Instant T2 = Instant.parse("2026-11-14T00:00:20Z");
  static final Instant T3 = Instant.parse("2026-11-14T00:00:30Z");

  @Autowired SiteStore sites;
  @Autowired ClusterStore clusters;
  @Autowired K8sResourceStore resources;
  @Autowired K8sResourceHistoryStore history;

  String cluster;
  String uid;

  @BeforeEach
  void cluster() {
    cluster = "cid-" + run;
    uid = "pod-" + run;
    sites.ensureExists("S-" + run);
    clusters.ensureExists(new ClusterSnapshot("S-" + run, cluster, "plant-" + run, null));
  }

  K8sResourceSnapshot snapshot(
      String uid, String kind, String phase, int restarts, Instant at, Instant deletedAt) {
    return new K8sResourceSnapshot(
        cluster,
        uid,
        "shop",
        kind,
        "name-" + uid,
        "v1",
        restarts,
        phase,
        Fakes.json("{\"app\":\"shop\"}"),
        null,
        null,
        null,
        at,
        deletedAt);
  }

  K8sResourceSnapshot pod(String phase, int restarts, Instant at) {
    return snapshot(uid, "Pod", phase, restarts, at, null);
  }

  java.util.Map<String, Object> resource(String uid) {
    return row("SELECT * FROM k8s_resources WHERE resource_uid=?", uid);
  }

  // ---------- upsert ----------

  @Test
  void upsertInsertsAndReturnsTheRowIdWithJsonbRoundTrip() {
    long id = resources.upsert(pod("Running", 2, T1));

    var r = resource(uid);
    assertThat(((Number) r.get("id")).longValue()).isEqualTo(id);
    assertThat(r.get("phase")).isEqualTo("Running");
    assertThat(((Number) r.get("restart_count")).intValue()).isEqualTo(2);
    assertThat(r.get("labels").toString()).contains("\"app\"").contains("shop");
    assertThat(r.get("spec")).isNull();
    assertThat(r.get("deleted_at")).isNull();
    assertThat(instant("SELECT observed_at FROM k8s_resources WHERE resource_uid=?", uid))
        .isEqualTo(T1);
  }

  @Test
  void aNewerObservationOverwritesAndAnOlderOneNeverDoesButStillReturnsTheId() {
    long id = resources.upsert(pod("Pending", 0, T2));

    long sameId = resources.upsert(pod("Running", 5, T3));
    assertThat(sameId).isEqualTo(id);
    assertThat(resource(uid).get("phase")).isEqualTo("Running");

    long idForOld = resources.upsert(pod("Failed", 9, T1)); // older than what is stored
    assertThat(idForOld).isEqualTo(id);
    assertThat(resource(uid).get("phase")).isEqualTo("Running");
    assertThat(((Number) resource(uid).get("restart_count")).intValue()).isEqualTo(5);
    assertThat(count("SELECT count(*) FROM k8s_resources WHERE resource_uid=?", uid)).isEqualTo(1);
  }

  @Test
  void anObservationAtTheSameInstantIsAccepted() {
    resources.upsert(pod("Pending", 0, T1));
    resources.upsert(pod("Running", 1, T1));

    assertThat(resource(uid).get("phase")).isEqualTo("Running");
  }

  @Test
  void aDeletionTimestampIsStoredAndAClearingObservationResetsIt() {
    resources.upsert(snapshot(uid, "Pod", "Running", 0, T1, T1));
    assertThat(instant("SELECT deleted_at FROM k8s_resources WHERE resource_uid=?", uid))
        .isEqualTo(T1);

    resources.upsert(pod("Running", 0, T2));
    assertThat(resource(uid).get("deleted_at")).isNull();
  }

  @Test
  void upsertForAnUnknownClusterFails() {
    var unknown =
        new K8sResourceSnapshot(
            "no-such-cluster-" + run,
            uid,
            "shop",
            "Pod",
            "p",
            "v1",
            0,
            null,
            null,
            null,
            null,
            null,
            T1,
            null);

    assertThatThrownBy(() -> resources.upsert(unknown)).isInstanceOf(RuntimeException.class);
  }

  // ---------- stub ----------

  @Test
  void aStubIsCreatedOnceAndTheRealObservationOverwritesIt() {
    String rs = "rs-" + run;
    long stub = resources.ensureStub(cluster, "ReplicaSet", "shop-rs", rs, "shop");
    assertThat(resources.ensureStub(cluster, "ReplicaSet", "other-name", rs, "shop"))
        .isEqualTo(stub);
    assertThat(instant("SELECT observed_at FROM k8s_resources WHERE resource_uid=?", rs))
        .isEqualTo(Instant.EPOCH);

    long real = resources.upsert(snapshot(rs, "ReplicaSet", null, 0, T1, null));

    assertThat(real).isEqualTo(stub);
    assertThat(resource(rs).get("resource_name")).isEqualTo("name-" + rs);
    assertThat(instant("SELECT observed_at FROM k8s_resources WHERE resource_uid=?", rs))
        .isEqualTo(T1);
  }

  @Test
  void aStubNeverOverwritesARealRow() {
    long id = resources.upsert(pod("Running", 1, T1));

    assertThat(resources.ensureStub(cluster, "Pod", "stub-name", uid, "shop")).isEqualTo(id);
    assertThat(resource(uid).get("resource_name")).isEqualTo("name-" + uid);
  }

  // ---------- relationships ----------

  @Test
  void linkOwnerIsIdempotent() {
    long parent = resources.upsert(snapshot("rs-" + run, "ReplicaSet", null, 0, T1, null));
    long child = resources.upsert(pod("Running", 0, T1));

    resources.linkOwner(parent, child);
    resources.linkOwner(parent, child);

    assertThat(
            count(
                "SELECT count(*) FROM k8s_resource_relationships WHERE parent_resource_id=? AND child_resource_id=? AND relationship_type='OWNS'",
                parent,
                child))
        .isEqualTo(1);
  }

  // ---------- containers ----------

  PodContainerSnapshot container(String name, boolean init, int restarts, String state) {
    return new PodContainerSnapshot(
        name,
        init,
        "acr/shop:1",
        "acr/shop@sha256:aaa",
        "sha256:aaa",
        true,
        restarts,
        state,
        null,
        "OOMKilled",
        137,
        T1);
  }

  @Test
  void containersAreKeyedByNameAndInitFlagAndOnlyNewerObservationsOverwrite() {
    long pod = resources.upsert(pod("Running", 0, T1));

    resources.upsertContainer(pod, container("app", false, 1, "running"), T2);
    resources.upsertContainer(pod, container("app", true, 0, "terminated"), T2); // same name, init
    resources.upsertContainer(pod, container("app", false, 4, "waiting"), T3); // newer → overwrites
    resources.upsertContainer(pod, container("app", false, 9, "running"), T1); // older → ignored

    assertThat(count("SELECT count(*) FROM k8s_pod_containers WHERE k8s_resource_id=?", pod))
        .isEqualTo(2);
    var main =
        row("SELECT * FROM k8s_pod_containers WHERE k8s_resource_id=? AND is_init=false", pod);
    assertThat(((Number) main.get("restart_count")).intValue()).isEqualTo(4);
    assertThat(main.get("state")).isEqualTo("waiting");
    assertThat(main.get("image_digest")).isEqualTo("sha256:aaa");
    assertThat(main.get("last_terminated_reason")).isEqualTo("OOMKilled");
    assertThat(((Number) main.get("last_exit_code")).intValue()).isEqualTo(137);
    assertThat(
            instant(
                "SELECT last_terminated_at FROM k8s_pod_containers WHERE k8s_resource_id=? AND is_init=false",
                pod))
        .isEqualTo(T1);
  }

  @Test
  void containersAreRemovedWithTheirPod() {
    long pod = resources.upsert(pod("Running", 0, T1));
    resources.upsertContainer(pod, container("app", false, 1, "running"), T2);

    jdbc.sql("DELETE FROM k8s_resources WHERE id=?").param(pod).update();

    assertThat(count("SELECT count(*) FROM k8s_pod_containers WHERE k8s_resource_id=?", pod))
        .isZero();
  }

  // ---------- missing from a complete list ----------

  int mark(String namespace, String kind, List<String> live, Instant at) {
    return resources.markMissingDeleted(cluster, namespace, kind, live, at);
  }

  @Test
  void markingAResourceDeletedClosesItsCurrentHistoryRowAtThatTime() {
    String a = "a-" + run;
    String b = "b-" + run;
    long idA = resources.upsert(snapshot(a, "Pod", "Running", 0, T1, null));
    long idB = resources.upsert(snapshot(b, "Pod", "Running", 0, T1, null));
    var state = new K8sResourceState("Running", 0, null, null, List.of(), false);
    history.record(idA, state, T1);
    history.record(idB, state, T1);

    mark("shop", "Pod", List.of(a), T2); // b is gone

    assertThat(instant("SELECT valid_to FROM k8s_resource_history WHERE k8s_resource_id=?", idB))
        .isEqualTo(T2);
    assertThat(instant("SELECT valid_to FROM k8s_resource_history WHERE k8s_resource_id=?", idA))
        .isNull();
  }

  @Test
  void markMissingDeletedMarksOnlyAbsentRowsAndKeepsThem() {
    String a = "a-" + run;
    String b = "b-" + run;
    resources.upsert(snapshot(a, "Pod", "Running", 0, T1, null));
    resources.upsert(snapshot(b, "Pod", "Running", 0, T1, null));

    assertThat(mark("shop", "Pod", List.of(a), T2)).isEqualTo(1);

    assertThat(instant("SELECT deleted_at FROM k8s_resources WHERE resource_uid=?", b))
        .isEqualTo(T2);
    assertThat(resource(a).get("deleted_at")).isNull();
    assertThat(resource(b).get("phase")).isEqualTo("Running"); // the row and its data remain
  }

  @Test
  void markMissingDeletedWithAnEmptyListMarksEverythingOfThatKindInTheNamespace() {
    resources.upsert(pod("Running", 0, T1));
    resources.upsert(snapshot("other-" + run, "Pod", "Running", 0, T1, null));

    assertThat(mark("shop", "Pod", List.of(), T2)).isEqualTo(2);
  }

  @Test
  void markMissingDeletedRespectsNamespaceKindAndObservationTime() {
    String newer = "newer-" + run;
    resources.upsert(pod("Running", 0, T1));
    resources.upsert(snapshot(newer, "Pod", "Running", 0, T3, null)); // observed after the list
    resources.upsert(snapshot("dep-" + run, "Deployment", null, 0, T1, null)); // other kind

    assertThat(mark("other-namespace", "Pod", List.of(), T2)).isZero();
    assertThat(mark("shop", "ReplicaSet", List.of(), T2)).isZero();
    assertThat(mark("shop", "Pod", List.of(), T2)).isEqualTo(1); // only the pod observed at T1

    assertThat(resource(newer).get("deleted_at")).isNull();
    assertThat(resource("dep-" + run).get("deleted_at")).isNull();
  }

  @Test
  void markMissingDeletedDoesNotRestampRowsThatAreAlreadyMarked() {
    resources.upsert(pod("Running", 0, T1));
    mark("shop", "Pod", List.of(), T2);

    assertThat(mark("shop", "Pod", List.of(), T3)).isZero();
    assertThat(instant("SELECT deleted_at FROM k8s_resources WHERE resource_uid=?", uid))
        .isEqualTo(T2);
  }

  @Test
  void markMissingDeletedForAnUnknownClusterAffectsNothing() {
    resources.upsert(pod("Running", 0, T1));

    assertThat(resources.markMissingDeleted("nope-" + run, "shop", "Pod", List.of(), T2)).isZero();
    assertThat(resource(uid).get("deleted_at")).isNull();
  }
}
