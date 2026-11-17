package io.cmdb.collector.normalization.infrastructure;

import io.cmdb.collector.leader.domain.LeaderGate;
import io.cmdb.collector.normalization.application.PendingEventSweeper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The leader periodically resubmits events whose processing did not complete (after a process
 * restart or a transient error). Only the instance holding the Lease runs it, so several instances
 * do not resubmit the same event; handlers are idempotent, so duplicates cannot corrupt data.
 */
@Component
class NormalizationScheduler {

  static final String LOCK = "cmdb-collector-normalize";

  private final IncompleteEventPublications incomplete;
  private final LeaderGate leader;
  private final PendingEventSweeper sweeper;
  private final Duration minAge;
  private final Duration pendingMinAge;
  private final int pendingBatchSize;

  NormalizationScheduler(
      IncompleteEventPublications incomplete,
      LeaderGate leader,
      PendingEventSweeper sweeper,
      @Value("${collector.normalization.resubmit-min-age:60s}") Duration minAge,
      @Value("${collector.normalization.pending-min-age:10m}") Duration pendingMinAge,
      @Value("${collector.normalization.pending-batch-size:500}") int pendingBatchSize) {
    this.incomplete = incomplete;
    this.leader = leader;
    this.sweeper = sweeper;
    this.minAge = minAge;
    this.pendingMinAge = pendingMinAge;
    this.pendingBatchSize = pendingBatchSize;
  }

  @Scheduled(
      fixedDelayString = "${collector.normalization.resubmit-interval:60s}",
      initialDelayString = "${collector.normalization.resubmit-initial-delay:30s}")
  void resubmit() {
    if (leader.isLeader(LOCK)) {
      incomplete.resubmitIncompletePublications(
          ResubmissionOptions.defaults().withMinAge(minAge).withBatchSize(200));
    }
  }

  /** Events landed before a handler existed (they have no publication to resubmit). */
  @Scheduled(
      fixedDelayString = "${collector.normalization.pending-sweep-interval:5m}",
      initialDelayString = "${collector.normalization.pending-initial-delay:2m}")
  void sweepPending() {
    if (leader.isLeader(LOCK)) {
      sweeper.sweep(pendingMinAge, pendingBatchSize);
    }
  }
}
