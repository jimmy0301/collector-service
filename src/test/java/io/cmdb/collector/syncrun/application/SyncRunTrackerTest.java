package io.cmdb.collector.syncrun.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.support.Fakes.InMemoryRuns;
import io.cmdb.collector.syncrun.domain.SyncOutcome;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.syncrun.domain.SyncType;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class SyncRunTrackerTest {

  final InMemoryRuns runs = new InMemoryRuns();
  final SyncRunTracker tracker = new SyncRunTracker(runs, Clock.systemUTC());

  @Test
  void aSuccessfulRunReportsSuccessWithoutAnError() {
    var result = tracker.track("ARGO:A", SyncType.POLL, () -> SyncOutcome.success(3, null));

    assertThat(result.provider()).isEqualTo("ARGO:A");
    assertThat(result.status()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(result.error()).isNull();
    assertThat(runs.last().status()).isEqualTo(SyncStatus.SUCCESS);
  }

  @Test
  void aPartialRunReportsTheReasonTheTaskGave() {
    var result =
        tracker.track(
            "ARGO:A", SyncType.POLL, () -> SyncOutcome.partial(2, "tree shop: 502", null));

    assertThat(result.status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(result.error()).isEqualTo("tree shop: 502");
    assertThat(runs.last().error()).isEqualTo("tree shop: 502"); // the same text is stored
  }

  @Test
  void aThrowingTaskIsReportedAsFailedWithTheException() {
    var result =
        tracker.track(
            "K8S:A",
            SyncType.POLL,
            () -> {
              throw new IllegalStateException("hub down");
            });

    assertThat(result.status()).isEqualTo(SyncStatus.FAILED);
    assertThat(result.error()).contains("IllegalStateException").contains("hub down");
    assertThat(runs.last().status()).isEqualTo(SyncStatus.FAILED);
  }

  @Test
  void aLongProviderNameIsCutToTheColumnLength() {
    var result = tracker.track("X".repeat(80), SyncType.POLL, () -> SyncOutcome.success(0, null));

    assertThat(result.provider()).hasSize(50);
  }
}
