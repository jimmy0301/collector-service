package io.cmdb.collector.syncrun.application;

import io.cmdb.collector.syncrun.domain.SyncRunRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every poll of every source writes one sync_runs row, and the shared database must be spared:
 * purge periodically in small batches (avoiding long transactions).
 */
@Component
class SyncRunRetention {

  private static final Logger log = LoggerFactory.getLogger(SyncRunRetention.class);
  private static final int BATCH = 1000;

  private final SyncRunRepository runs;
  private final Clock clock;
  private final int retentionDays;

  SyncRunRetention(
      SyncRunRepository runs,
      Clock clock,
      @Value("${collector.sync-run-retention-days:7}") int retentionDays) {
    this.runs = runs;
    this.clock = clock;
    this.retentionDays = retentionDays;
  }

  @Scheduled(cron = "${collector.sync-run-purge-cron:0 17 3 * * *}", zone = "UTC")
  void purge() {
    var cutoff = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
    int total = 0;
    int deleted;
    do {
      deleted = runs.deleteStartedBefore(cutoff, BATCH);
      total += deleted;
    } while (deleted == BATCH);
    log.info("purged {} integration_sync_runs older than {} days", total, retentionDays);
  }
}
