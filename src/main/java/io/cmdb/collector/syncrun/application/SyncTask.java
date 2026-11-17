package io.cmdb.collector.syncrun.application;

import io.cmdb.collector.syncrun.domain.SyncOutcome;

@FunctionalInterface
public interface SyncTask {
  SyncOutcome run() throws Exception;
}
