package io.cmdb.collector.rawevent.application;

import io.cmdb.collector.rawevent.domain.RawEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * State transitions for the outcome of processing a raw event. Success and failure have different
 * transaction semantics, hence two methods.
 */
@Service
public class RawEventLifecycle {

  private static final int MAX_ERROR_LENGTH = 2000;

  private final RawEventRepository rawEvents;

  public RawEventLifecycle(RawEventRepository rawEvents) {
    this.rawEvents = rawEvents;
  }

  /**
   * In the same transaction as the normalization writes: the domain table writes and PROCESSED
   * commit together.
   */
  @Transactional
  public void markProcessed(Long id) {
    rawEvents.markProcessed(id);
  }

  /**
   * Records a failure. Must open a new transaction: the caller's transaction rolls back on the
   * exception, and the failure record must not disappear with it.
   *
   * @return true if {@code maxAttempts} was exceeded and the event was marked DEAD (do not retry)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean recordFailure(Long id, String error, int maxAttempts) {
    String message =
        error == null || error.length() <= MAX_ERROR_LENGTH
            ? error
            : error.substring(0, MAX_ERROR_LENGTH);
    int attempts = rawEvents.recordFailure(id, message);
    if (attempts >= maxAttempts) {
      rawEvents.markDead(id, message);
      return true;
    }
    return false;
  }
}
