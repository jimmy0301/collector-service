package io.cmdb.collector.normalization.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.normalization.application.RecordingStores.Deletion;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.support.Fakes;
import io.cmdb.collector.support.TestPayloads;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class K8sResourceListHandlerTest {

  final TestPayloads payloads = new TestPayloads("t");
  final RecordingStores.Resources resources = new RecordingStores.Resources();
  final K8sResourceListHandler handler = new K8sResourceListHandler(resources);

  @Test
  void passesTheLiveUidsAndTheListTimeToTheStore() {
    handler.handle(
        new RawEvent(
            "K8S",
            "RESOURCE_LIST",
            "l1",
            "k",
            Fakes.json(payloads.list("2026-11-14T00:01:00Z", "Pod", "[\"a\",\"b\"]"))));

    assertThat(resources.deletions)
        .containsExactly(
            new Deletion(
                payloads.clusterId,
                "shop",
                "Pod",
                List.of("a", "b"),
                Instant.parse("2026-11-14T00:01:00Z")));
  }

  @Test
  void anEmptyListIsPassedAsIs() {
    handler.handle(
        new RawEvent(
            "K8S",
            "RESOURCE_LIST",
            "l2",
            "k",
            Fakes.json(payloads.list("2026-11-14T00:01:00Z", "Pod", "[]"))));

    assertThat(resources.deletions.get(0).liveUids()).isEmpty();
  }

  @Test
  void supportsOnlyK8sResourceList() {
    assertThat(handler.supports("K8S", "RESOURCE_LIST")).isTrue();
    assertThat(handler.supports("K8S", "Pod")).isFalse();
    assertThat(handler.supports("ARGO", "RESOURCE_LIST")).isFalse();
  }
}
