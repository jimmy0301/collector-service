package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.inventory.domain.site.SiteSnapshot;
import io.cmdb.collector.rawevent.domain.RawEvent;
import org.junit.jupiter.api.Test;

class SiteSnapshotHandlerTest {

  final RecordingStores.Sites sites = new RecordingStores.Sites();
  final SiteSnapshotHandler handler = new SiteSnapshotHandler(sites);

  static RawEvent event(String payload) {
    return new RawEvent("ARGO", "SITE_SNAPSHOT", "s1", "argo:sites", json(payload));
  }

  @Test
  void supportsSiteSnapshotsFromBothHubsOnly() {
    assertThat(handler.supports("ARGO", "SITE_SNAPSHOT")).isTrue();
    assertThat(handler.supports("K8S", "SITE_SNAPSHOT")).isTrue();
    assertThat(handler.supports("K8S", "CLUSTER_SNAPSHOT")).isFalse();
    assertThat(handler.supports("ADO", "SITE_SNAPSHOT")).isFalse();
  }

  @Test
  void everyItemBecomesASiteUpsert() {
    handler.handle(
        event(
            "{\"items\":[{\"code\":\"A\",\"name\":\"A 廠\",\"region\":\"north\"},{\"code\":\"B\"}]}"));

    assertThat(sites.upserts)
        .containsExactly(new SiteSnapshot("A", "A 廠", "north"), new SiteSnapshot("B", null, null));
  }

  @Test
  void anItemWithoutACodeFailsTheEvent() {
    assertThatThrownBy(() -> handler.handle(event("{\"items\":[{\"name\":\"x\"}]}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("code");
  }
}
