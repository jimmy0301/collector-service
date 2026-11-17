package io.cmdb.collector.rawevent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jmolecules.ddd.annotation.Repository;

/** Repository of RawEvent (plain Java, no framework dependency). */
@Repository
public interface RawEventRepository {

  /**
   * Stores the event only if (provider, externalEventId) does not exist yet.
   *
   * <p>Does not use save(): on a unique key conflict JPA throws and rolls the transaction back, and
   * every poll round has many duplicates, so the database skips them atomically.
   *
   * @return the id of the newly written event; empty if it already exists (idempotent
   *     deduplication)
   */
  Optional<Long> saveIfAbsent(RawEvent event);

  Optional<RawEvent> findById(Long id);

  Optional<RawEvent> findByProviderAndExternalEventId(String provider, String externalEventId);

  /**
   * Marks PROCESSED (process_status state machine: PENDING → PROCESSED, or FAILED → DEAD, see
   * PLAN.md 3.1).
   */
  void markProcessed(Long id);

  /**
   * Marks FAILED, increments attempt_count and records the error. @return the accumulated number of
   * failures
   */
  int recordFailure(Long id, String error);

  /** Retry limit exceeded: marks DEAD (needs manual handling and an alert). */
  void markDead(Long id, String error);

  /**
   * The (provider, event type) pairs that have events still PENDING and received before {@code
   * receivedBefore} (recent ones are probably being processed right now).
   */
  List<PendingKind> pendingKinds(Instant receivedBefore);

  /** The oldest PENDING events of one kind received before {@code receivedBefore}, by id. */
  List<RawEvent> findPending(String provider, String eventType, Instant receivedBefore, int limit);
}
