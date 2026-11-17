package io.cmdb.collector.syncrun.infrastructure;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.support.DatabaseTest;
import io.cmdb.collector.syncrun.domain.SyncRun;
import io.cmdb.collector.syncrun.domain.SyncRunRepository;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.syncrun.domain.SyncType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(JpaSyncRunRepository.class)
class JpaSyncRunRepositoryIT extends DatabaseTest {

  @Autowired SyncRunRepository syncRuns;
  @Autowired EntityManager em;

  @Test
  void syncRunLifecycleStoresStatusCursorAndInstants() {
    Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    SyncRun run = SyncRun.start("ARGO:PLANT-A", SyncType.POLL, start);
    syncRuns.save(run);

    assertThat(run.id()).isNotNull(); // the database-generated id is available after insert
    assertThat(
            jdbc.sql("SELECT status FROM integration_sync_runs WHERE id=?")
                .param(run.id())
                .query(String.class)
                .single())
        .isEqualTo("RUNNING");

    run.finish(
        SyncStatus.PARTIAL, 12, "one tree failed", json("{\"newEvents\":3}"), start.plusSeconds(2));
    syncRuns.save(run);
    em.flush(); // the test transaction is rolled back, so push the update to the database now
    em.clear(); // and make the next find really read it back

    var row =
        jdbc.sql("SELECT * FROM integration_sync_runs WHERE id=?")
            .param(run.id())
            .query()
            .singleRow();
    assertThat(row.get("status")).isEqualTo("PARTIAL");
    assertThat(row.get("sync_type")).isEqualTo("POLL");
    assertThat(((Number) row.get("records_processed")).intValue()).isEqualTo(12);
    assertThat(row.get("error_message")).isEqualTo("one tree failed");
    assertThat(row.get("finished_at")).isNotNull();
    assertThat(json(row.get("cursor_state").toString()).path("newEvents").asInt()).isEqualTo(3);

    // Round trip: the Instant is not shifted by a time zone
    SyncRun loaded = syncRuns.findById(run.id()).orElseThrow();
    assertThat(loaded.startedAt()).isEqualTo(start);
    assertThat(loaded.finishedAt()).isEqualTo(start.plusSeconds(2));
    assertThat(loaded.status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(loaded.cursor().path("newEvents").asInt()).isEqualTo(3);
    Long driftSeconds =
        jdbc.sql(
                "SELECT ABS(EXTRACT(EPOCH FROM (now() - started_at)))::bigint FROM integration_sync_runs WHERE id=?")
            .param(run.id())
            .query(Long.class)
            .single();
    assertThat(driftSeconds).isLessThan(10);
  }

  @Test
  void deleteStartedBeforeRemovesOnlyOldRunsInBatches() {
    Instant now = Instant.now();
    for (int i = 0; i < 3; i++) {
      syncRuns.save(SyncRun.start("OLD", SyncType.POLL, now.minus(10, ChronoUnit.DAYS)));
    }
    SyncRun recent = SyncRun.start("RECENT", SyncType.POLL, now);
    syncRuns.save(recent);
    Instant cutoff = now.minus(7, ChronoUnit.DAYS);

    assertThat(syncRuns.deleteStartedBefore(cutoff, 2)).isEqualTo(2);
    assertThat(syncRuns.deleteStartedBefore(cutoff, 2)).isEqualTo(1);
    assertThat(syncRuns.deleteStartedBefore(cutoff, 2)).isZero();

    assertThat(syncRuns.findById(recent.id())).isPresent();
  }
}
