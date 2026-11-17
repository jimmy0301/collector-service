package io.cmdb.collector.syncrun.application;

import io.cmdb.collector.syncrun.domain.SyncOutcome;
import io.cmdb.collector.syncrun.domain.SyncResult;
import io.cmdb.collector.syncrun.domain.SyncRun;
import io.cmdb.collector.syncrun.domain.SyncRunRepository;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.syncrun.domain.SyncType;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Wraps one sync: records start / end / error and isolates exceptions within a single source, so a
 * failure at one site does not interrupt the others.
 */
@Service
public class SyncRunTracker {

  private static final Logger log = LoggerFactory.getLogger(SyncRunTracker.class);
  private static final int MAX_ERROR_LENGTH = 2000;

  /** integration_sync_runs.provider is VARCHAR(50). */
  private static final int MAX_PROVIDER_LENGTH = 50;

  private final SyncRunRepository runs;
  private final Clock clock;

  public SyncRunTracker(SyncRunRepository runs, Clock clock) {
    this.runs = runs;
    this.clock = clock;
  }

  /**
   * Runs the task and records it. Never throws; the result says how the run ended and why it did
   * not succeed, so a caller (such as the on-demand trigger API) can report it.
   */
  public SyncResult track(String source, SyncType type, SyncTask task) {
    String provider =
        source.length() > MAX_PROVIDER_LENGTH ? source.substring(0, MAX_PROVIDER_LENGTH) : source;
    SyncRun run = SyncRun.start(provider, type, clock.instant());
    try {
      runs.save(run);
    } catch (RuntimeException e) {
      log.error("[{}] cannot record sync run start (database unavailable?)", provider, e);
      return new SyncResult(
          provider, SyncStatus.FAILED, "cannot record sync run start: " + describe(e));
    }
    try {
      SyncOutcome outcome = task.run();
      run.finish(
          outcome.status(),
          outcome.records(),
          truncate(outcome.error()),
          outcome.cursor(),
          clock.instant());
      runs.save(run);
      if (outcome.status() != SyncStatus.SUCCESS) {
        log.warn("[{}] sync finished {}: {}", provider, outcome.status(), outcome.error());
      }
      return new SyncResult(provider, outcome.status(), truncate(outcome.error()));
    } catch (Exception e) {
      log.error("[{}] sync failed", provider, e);
      String error = truncate(describe(e));
      try {
        if (run.status() == SyncStatus.RUNNING) {
          run.finish(SyncStatus.FAILED, 0, error, null, clock.instant());
          runs.save(run);
        }
      } catch (RuntimeException inner) {
        log.error("[{}] cannot record sync run failure", provider, inner);
      }
      return new SyncResult(provider, SyncStatus.FAILED, error);
    }
  }

  private static String describe(Exception e) {
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  private static String truncate(String s) {
    return s == null || s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
  }
}
