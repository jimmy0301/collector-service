package io.cmdb.collector.syncrun.domain;

/** Maps to integration_sync_runs.sync_type. */
public enum SyncType {
  FULL,
  INCREMENTAL,
  POLL,
  STREAM,
  WATCH,
  QUEUE
}
