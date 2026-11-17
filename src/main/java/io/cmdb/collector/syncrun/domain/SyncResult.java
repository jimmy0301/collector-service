package io.cmdb.collector.syncrun.domain;

/**
 * How one tracked sync run ended, as seen by the caller. {@code error} is the reason when the run
 * was PARTIAL or FAILED (the same text stored in integration_sync_runs.error_message).
 */
public record SyncResult(String provider, SyncStatus status, String error) {}
