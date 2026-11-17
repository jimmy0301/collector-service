package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.k8s.K8sEventSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sEventStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.support.DatabaseTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({
  JpaSiteStore.class,
  JpaClusterStore.class,
  JpaK8sResourceStore.class,
  JpaK8sEventStore.class
})
class JpaK8sEventStoreIT extends DatabaseTest {

  static final Instant T0 = Instant.parse("2026-11-14T00:00:00Z");
  static final Instant T1 = Instant.parse("2026-11-14T00:00:10Z");
  static final Instant T2 = Instant.parse("2026-11-14T00:00:20Z");
  static final Instant T3 = Instant.parse("2026-11-14T00:00:30Z");

  @Autowired SiteStore sites;
  @Autowired ClusterStore clusters;
  @Autowired K8sResourceStore resources;
  @Autowired K8sEventStore events;

  String cluster;
  String eventUid;
  String podUid;

  @BeforeEach
  void cluster() {
    cluster = "cid-" + run;
    eventUid = "ev-" + run;
    podUid = "pod-" + run;
    sites.ensureExists("S-" + run);
    clusters.ensureExists(new ClusterSnapshot("S-" + run, cluster, "plant-" + run, null));
  }

  K8sEventSnapshot event(
      String involvedUid, String reason, int count, Instant lastSeen, Instant observedAt) {
    return new K8sEventSnapshot(
        cluster,
        eventUid,
        "shop",
        "Pod",
        "shop-abc",
        involvedUid,
        "Warning",
        reason,
        "msg",
        count,
        T0,
        lastSeen,
        observedAt);
  }

  long knownPod() {
    return resources.upsert(
        new K8sResourceSnapshot(
            cluster,
            podUid,
            "shop",
            "Pod",
            "shop-abc",
            "v1",
            0,
            "Running",
            null,
            null,
            null,
            null,
            T0,
            null));
  }

  java.util.Map<String, Object> stored() {
    return row("SELECT * FROM k8s_events WHERE event_uid=?", eventUid);
  }

  @Test
  void upsertStoresTheEventAndLinksItToTheKnownResource() {
    long pod = knownPod();

    events.upsert(event(podUid, "BackOff", 1, T1, T1));

    var r = stored();
    assertThat(r.get("reason")).isEqualTo("BackOff");
    assertThat(r.get("event_type")).isEqualTo("Warning");
    assertThat(((Number) r.get("event_count")).intValue()).isEqualTo(1);
    assertThat(((Number) r.get("k8s_resource_id")).longValue()).isEqualTo(pod);
    assertThat(instant("SELECT first_seen_at FROM k8s_events WHERE event_uid=?", eventUid))
        .isEqualTo(T0);
    assertThat(instant("SELECT last_seen_at FROM k8s_events WHERE event_uid=?", eventUid))
        .isEqualTo(T1);
  }

  @Test
  void anEventForAnUnknownResourceIsStoredWithoutALink() {
    events.upsert(event("nobody-" + run, "BackOff", 1, T1, T1));

    assertThat(stored().get("k8s_resource_id")).isNull();
    assertThat(stored().get("service_id")).isNull();
  }

  @Test
  void aNewerVersionUpdatesTheSameRowAndAnOlderOneNeverOverwrites() {
    events.upsert(event(podUid, "BackOff", 1, T1, T1));

    events.upsert(event(podUid, "BackOff", 5, T3, T3));
    assertThat(((Number) stored().get("event_count")).intValue()).isEqualTo(5);
    assertThat(instant("SELECT last_seen_at FROM k8s_events WHERE event_uid=?", eventUid))
        .isEqualTo(T3);

    events.upsert(event(podUid, "Old", 2, T2, T2));
    assertThat(stored().get("reason")).isEqualTo("BackOff");
    assertThat(((Number) stored().get("event_count")).intValue()).isEqualTo(5);
    assertThat(count("SELECT count(*) FROM k8s_events WHERE event_uid=?", eventUid)).isEqualTo(1);
  }

  @Test
  void aResolvedLinkIsNeverClearedAndAMissingOneIsFilledInLater() {
    events.upsert(event(podUid, "BackOff", 1, T1, T1)); // pod not known yet
    assertThat(stored().get("k8s_resource_id")).isNull();

    long pod = knownPod();
    events.upsert(event(podUid, "BackOff", 2, T2, T2)); // the next version resolves the link
    assertThat(((Number) stored().get("k8s_resource_id")).longValue()).isEqualTo(pod);

    events.upsert(event("nobody-" + run, "BackOff", 3, T3, T3)); // would resolve to nothing
    assertThat(((Number) stored().get("k8s_resource_id")).longValue()).isEqualTo(pod);
  }

  @Test
  void theFirstSeenTimeIsKept() {
    events.upsert(event(podUid, "BackOff", 1, T1, T1));
    events.upsert(
        new K8sEventSnapshot(
            cluster,
            eventUid,
            "shop",
            "Pod",
            "shop-abc",
            podUid,
            "Warning",
            "BackOff",
            "msg",
            2,
            T2,
            T3,
            T3));

    assertThat(instant("SELECT first_seen_at FROM k8s_events WHERE event_uid=?", eventUid))
        .isEqualTo(T0);
  }

  @Test
  void anEventForAnUnknownClusterStoresNothing() {
    events.upsert(
        new K8sEventSnapshot(
            "nope-" + run,
            eventUid,
            "shop",
            "Pod",
            "p",
            podUid,
            "Warning",
            "BackOff",
            "msg",
            1,
            T0,
            T1,
            T1));

    assertThat(count("SELECT count(*) FROM k8s_events WHERE event_uid=?", eventUid)).isZero();
  }
}
