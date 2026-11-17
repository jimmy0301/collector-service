package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceHistoryStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceState;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceState.ContainerImage;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.support.DatabaseTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class JpaK8sResourceHistoryStoreIT extends DatabaseTest {

  static final Instant T1 = Instant.parse("2026-11-14T00:00:10Z");
  static final Instant T2 = Instant.parse("2026-11-14T00:00:20Z");
  static final Instant T3 = Instant.parse("2026-11-14T00:00:30Z");
  static final Instant T4 = Instant.parse("2026-11-14T00:00:40Z");

  @Autowired SiteStore sites;
  @Autowired ClusterStore clusters;
  @Autowired K8sResourceStore resources;
  @Autowired K8sResourceHistoryStore history;

  long podId;

  @BeforeEach
  void pod() {
    sites.ensureExists("S-" + run);
    clusters.ensureExists(new ClusterSnapshot("S-" + run, "cid-" + run, "plant-" + run, null));
    podId =
        resources.upsert(
            new K8sResourceSnapshot(
                "cid-" + run,
                "pod-" + run,
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
                null));
  }

  static K8sResourceState pod(String phase, int restarts, String digest) {
    return new K8sResourceState(
        phase,
        restarts,
        null,
        null,
        List.of(new ContainerImage("app", "acr.io/shop:1", digest)),
        false);
  }

  List<Map<String, Object>> rows() {
    return jdbc.sql(
            """
            SELECT phase, restart_count, images->0->>'digest' AS digest, terminating,
                   to_char(valid_from AT TIME ZONE 'UTC','HH24:MI:SS') AS valid_from,
                   to_char(valid_to AT TIME ZONE 'UTC','HH24:MI:SS') AS valid_to
              FROM k8s_resource_history WHERE k8s_resource_id=? ORDER BY valid_from
            """)
        .param(podId)
        .query()
        .listOfRows();
  }

  @Test
  void theFirstObservationStartsTheCurrentRow() {
    history.record(podId, pod("Running", 0, "sha256:a"), T1);

    assertThat(rows())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.get("phase")).isEqualTo("Running");
              assertThat(r.get("digest")).isEqualTo("sha256:a");
              assertThat(r.get("valid_from")).isEqualTo("00:00:10");
              assertThat(r.get("valid_to")).isNull();
            });
  }

  @Test
  void theSameStateAgainAddsNothingEvenWithANewerObservation() {
    history.record(podId, pod("Running", 0, "sha256:a"), T1);
    history.record(podId, pod("Running", 0, "sha256:a"), T2);
    history.record(podId, pod("Running", 0, "sha256:a"), T3);

    assertThat(rows()).hasSize(1);
  }

  @Test
  void aChangeClosesTheCurrentRowAndStartsANewOneAtTheSameInstant() {
    history.record(podId, pod("Running", 0, "sha256:a"), T1);
    history.record(podId, pod("Running", 2, "sha256:a"), T2); // restarted twice
    history.record(podId, pod("Running", 2, "sha256:b"), T3); // new image

    assertThat(rows())
        .extracting(
            r -> r.get("restart_count"),
            r -> r.get("digest"),
            r -> r.get("valid_from"),
            r -> r.get("valid_to"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0, "sha256:a", "00:00:10", "00:00:20"),
            org.assertj.core.groups.Tuple.tuple(2, "sha256:a", "00:00:20", "00:00:30"),
            org.assertj.core.groups.Tuple.tuple(2, "sha256:b", "00:00:30", null));
  }

  @Test
  void eachFieldOfTheStateCountsAsAChange() {
    history.record(podId, pod("Pending", 0, "sha256:a"), T1);
    history.record(podId, pod("Running", 0, "sha256:a"), T2); // phase
    history.record(
        podId,
        new K8sResourceState("Running", 0, null, null, List.of(), true),
        T3); // terminating, and the images are gone

    assertThat(rows()).hasSize(3);
    assertThat(rows().get(2).get("terminating")).isEqualTo(true);
  }

  @Test
  void anOlderObservationNeverRewritesHistory() {
    history.record(podId, pod("Running", 0, "sha256:a"), T3);

    history.record(podId, pod("Pending", 0, "sha256:a"), T1); // arrived late

    assertThat(rows()).hasSize(1);
    assertThat(rows().get(0).get("phase")).isEqualTo("Running");
    assertThat(rows().get(0).get("valid_from")).isEqualTo("00:00:30");
  }

  @Test
  void anObservationInsideAClosedRangeIsIgnored() {
    history.record(podId, pod("Pending", 0, "sha256:a"), T1);
    history.record(podId, pod("Running", 0, "sha256:a"), T3);

    history.record(podId, pod("Failed", 0, "sha256:a"), T2); // belongs to the closed first range

    assertThat(rows()).extracting(r -> r.get("phase")).containsExactly("Pending", "Running");
  }

  @Test
  void anObservationAfterTheHistoryWasClosedStartsANewRow() {
    history.record(podId, pod("Running", 0, "sha256:a"), T1);
    jdbc.sql("UPDATE k8s_resource_history SET valid_to=? WHERE k8s_resource_id=?")
        .params(java.sql.Timestamp.from(T2), podId)
        .update();

    history.record(podId, pod("Running", 0, "sha256:a"), T3);

    assertThat(rows()).hasSize(2);
  }

  @Test
  void deploymentStatesWithNullFieldsAreComparedByValueNotDuplicated() {
    var deployment =
        new K8sResourceState(
            null, 0, 2, 2, List.of(new ContainerImage("app", "acr.io/shop:1", null)), false);
    history.record(podId, deployment, T1);
    history.record(podId, deployment, T2); // null phase and null digest must compare equal

    assertThat(rows()).hasSize(1);

    history.record(
        podId,
        new K8sResourceState(
            null, 0, 3, 2, List.of(new ContainerImage("app", "acr.io/shop:1", null)), false),
        T3);
    assertThat(rows()).hasSize(2);
  }

  @Test
  void aPointInTimeQueryFindsTheStateThatWasValidThen() {
    history.record(podId, pod("Pending", 0, "sha256:a"), T1);
    history.record(podId, pod("Running", 0, "sha256:a"), T3);

    String sql =
        """
        SELECT phase FROM k8s_resource_history
         WHERE k8s_resource_id=? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)
        """;
    var at2 = java.sql.Timestamp.from(T2);
    var at4 = java.sql.Timestamp.from(T4);
    assertThat(jdbc.sql(sql).params(podId, at2, at2).query(String.class).single())
        .isEqualTo("Pending");
    assertThat(jdbc.sql(sql).params(podId, at4, at4).query(String.class).single())
        .isEqualTo("Running");
  }
}
