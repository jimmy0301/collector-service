package io.cmdb.collector.trigger.domain;

import io.cmdb.collector.syncrun.domain.SyncStatus;
import java.util.List;

/**
 * What happened when one job ran. {@code runs} is how many sync runs were tracked (0 = nothing was
 * configured or in scope). {@code errors} says why the runs that did not succeed failed ("provider:
 * reason"), or why the job itself threw; the full record of each run is in integration_sync_runs.
 */
public record JobReport(
    String job, SyncStatus status, int runs, long durationMs, List<String> errors) {}
