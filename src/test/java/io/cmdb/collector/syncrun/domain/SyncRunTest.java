package io.cmdb.collector.syncrun.domain;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SyncRunTest {

  static final Instant T0 = Instant.parse("2026-11-14T00:00:00Z");
  static final Instant T1 = T0.plusSeconds(3);

  @Test
  void startsRunningWithoutFinishTime() {
    SyncRun run = SyncRun.start("ARGO:PLANT-A", SyncType.POLL, T0);

    assertThat(run.status()).isEqualTo(SyncStatus.RUNNING);
    assertThat(run.startedAt()).isEqualTo(T0);
    assertThat(run.finishedAt()).isNull();
    assertThat(run.id()).isNull();
  }

  @Test
  void finishRecordsResultAndTime() {
    SyncRun run = SyncRun.start("ARGO:PLANT-A", SyncType.POLL, T0);

    run.finish(SyncStatus.PARTIAL, 12, "one tree failed", json("{\"newEvents\":3}"), T1);

    assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(run.records()).isEqualTo(12);
    assertThat(run.error()).isEqualTo("one tree failed");
    assertThat(run.cursor().path("newEvents").asInt()).isEqualTo(3);
    assertThat(run.finishedAt()).isEqualTo(T1);
  }

  @Test
  void canOnlyFinishOnce() {
    SyncRun run = SyncRun.start("ARGO:PLANT-A", SyncType.POLL, T0);
    run.finish(SyncStatus.SUCCESS, 1, null, null, T1);

    assertThatThrownBy(() -> run.finish(SyncStatus.FAILED, 0, "x", null, T1))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cannotFinishAsRunning() {
    SyncRun run = SyncRun.start("ARGO:PLANT-A", SyncType.POLL, T0);

    assertThatThrownBy(() -> run.finish(SyncStatus.RUNNING, 0, null, null, T1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void providerIsRequired() {
    assertThatThrownBy(() -> SyncRun.start(" ", SyncType.POLL, T0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
