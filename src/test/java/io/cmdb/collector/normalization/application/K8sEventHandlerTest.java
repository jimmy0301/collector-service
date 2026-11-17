package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sEventSnapshot;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.support.TestPayloads;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class K8sEventHandlerTest {

  final TestPayloads payloads = new TestPayloads("t");
  final RecordingStores.Sites sites = new RecordingStores.Sites();
  final RecordingStores.Clusters clusters = new RecordingStores.Clusters();
  final RecordingStores.Events events = new RecordingStores.Events();
  final K8sEventHandler handler = new K8sEventHandler(sites, clusters, events);

  void handle(String fetchedAt, String object) {
    handler.handle(new RawEvent("K8S", "Event", "e", "k", json(payloads.k8s(fetchedAt, object))));
  }

  @Test
  void classicEventMapsEveryField() {
    handle("2026-11-14T00:01:00Z", payloads.event("1", 3, "2026-11-14T00:00:30Z", "BackOff"));

    assertThat(events.upserts)
        .containsExactly(
            new K8sEventSnapshot(
                payloads.clusterId,
                "ev-t",
                "shop",
                "Pod",
                "shop-abc",
                payloads.podUid,
                "Warning",
                "BackOff",
                "Back-off restarting failed container",
                3,
                Instant.parse("2026-11-14T00:00:00Z"),
                Instant.parse("2026-11-14T00:00:30Z"),
                Instant.parse("2026-11-14T00:01:00Z")));
  }

  @Test
  void ensuresTheSiteAndClusterBecauseTheClusterListMayNotHaveBeenProcessedYet() {
    handle("2026-11-14T00:01:00Z", payloads.event("1", 1, "2026-11-14T00:00:30Z", "BackOff"));

    assertThat(sites.ensured).containsExactly(payloads.site);
    assertThat(clusters.ensured)
        .containsExactly(new ClusterSnapshot(payloads.site, payloads.clusterId, "plant-a", null));
  }

  @Test
  void newStyleEventUsesEventTimeAndSeries() {
    handle(
        "2026-11-14T00:03:00Z",
        """
        {"metadata":{"uid":"u1","namespace":"shop","creationTimestamp":"2026-11-14T00:00:00Z"},
         "involvedObject":{"kind":"Pod","name":"gone","uid":"nobody"},
         "type":"Warning","reason":"Unhealthy","eventTime":"2026-11-14T00:02:00.123456Z",
         "series":{"count":7,"lastObservedTime":"2026-11-14T00:02:30.000000Z"}}
        """);

    K8sEventSnapshot e = events.upserts.get(0);
    assertThat(e.count()).isEqualTo(7);
    assertThat(e.firstSeenAt()).isEqualTo(Instant.parse("2026-11-14T00:02:00.123456Z"));
    assertThat(e.lastSeenAt()).isEqualTo(Instant.parse("2026-11-14T00:02:30Z"));
  }

  @Test
  void withoutAnyEventTimestampsTheCreationTimestampIsUsedAndCountIsAtLeastOne() {
    handle(
        "2026-11-14T00:03:00Z",
        """
        {"metadata":{"uid":"u2","creationTimestamp":"2026-11-14T00:00:00Z"},
         "involvedObject":{"kind":"Node","name":"n1"},"type":"Warning","reason":"X"}
        """);

    K8sEventSnapshot e = events.upserts.get(0);
    assertThat(e.firstSeenAt()).isEqualTo(Instant.parse("2026-11-14T00:00:00Z"));
    assertThat(e.lastSeenAt()).isEqualTo(e.firstSeenAt());
    assertThat(e.count()).isEqualTo(1);
    assertThat(e.involvedUid()).isNull();
  }

  @Test
  void theLargerOfCountAndSeriesCountWins() {
    handle(
        "2026-11-14T00:03:00Z",
        """
        {"metadata":{"uid":"u3","creationTimestamp":"2026-11-14T00:00:00Z"},
         "involvedObject":{"kind":"Pod"},"count":4,"series":{"count":9}}
        """);

    assertThat(events.upserts.get(0).count()).isEqualTo(9);
  }

  @Test
  void supportsOnlyK8sEvents() {
    assertThat(handler.supports("K8S", "Event")).isTrue();
    assertThat(handler.supports("K8S", "Pod")).isFalse();
  }
}
