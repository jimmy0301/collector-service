package io.cmdb.collector.syncrun.domain;

/**
 * RUNNING: in progress (appears only during the {@link SyncRun} lifecycle); SUCCESS: everything
 * completed; PARTIAL: some items failed (freshness checks must not treat it as a complete success);
 * FAILED: the whole run failed.
 */
public enum SyncStatus {
  RUNNING,
  SUCCESS,
  PARTIAL,
  FAILED
}
