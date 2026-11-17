package io.cmdb.collector.syncrun.domain;

import tools.jackson.databind.JsonNode;

public record SyncOutcome(SyncStatus status, int records, String error, JsonNode cursor) {

  public static SyncOutcome success(int records, JsonNode cursor) {
    return new SyncOutcome(SyncStatus.SUCCESS, records, null, cursor);
  }

  public static SyncOutcome partial(int records, String error, JsonNode cursor) {
    return new SyncOutcome(SyncStatus.PARTIAL, records, error, cursor);
  }
}
