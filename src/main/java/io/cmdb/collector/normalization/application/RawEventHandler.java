package io.cmdb.collector.normalization.application;

import io.cmdb.collector.rawevent.domain.RawEvent;

/**
 * Normalizes one kind of raw event into the domain tables. Implementations must be idempotent
 * (resubmission and sweeps process the same event again).
 */
interface RawEventHandler {

  boolean supports(String provider, String eventType);

  /**
   * Runs inside the caller's transaction; throwing means this attempt failed (the transaction rolls
   * back and is retried later).
   */
  void handle(RawEvent event);
}
