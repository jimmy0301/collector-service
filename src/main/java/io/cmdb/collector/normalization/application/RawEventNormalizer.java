package io.cmdb.collector.normalization.application;

import io.cmdb.collector.rawevent.application.RawEventLifecycle;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventRepository;
import io.cmdb.collector.rawevent.domain.RawEventStored;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Entry point of normalization once a raw event has been stored.
 *
 * <p>Trigger: {@link RawEventStored} (a Spring event persisted by the Modulith event publication
 * registry; unfinished events are resubmitted after a restart or periodically by the leader).
 * Execution: asynchronous, in a new transaction, after commit; events with the same ordering_key
 * are serialized by a lock.
 *
 * <p>Failure: recorded on the raw event (FAILED, attempt_count, last_error) and the exception is
 * rethrown so the publication stays incomplete and is resubmitted later; after {@code max-attempts}
 * the event is marked DEAD and retries stop. Partial writes of a DEAD event inside the transaction
 * are not rolled back.
 *
 * <p>Event types that have no handler are skipped and the raw event stays PENDING; {@link
 * PendingEventSweeper} submits them once a handler exists.
 */
@Component
class RawEventNormalizer {

  private static final Logger log = LoggerFactory.getLogger(RawEventNormalizer.class);

  private final List<RawEventHandler> handlers;
  private final RawEventRepository rawEvents;
  private final RawEventLifecycle lifecycle;
  private final int maxAttempts;
  private final KeyedLocks locks = new KeyedLocks(64);

  RawEventNormalizer(
      List<RawEventHandler> handlers,
      RawEventRepository rawEvents,
      RawEventLifecycle lifecycle,
      @Value("${collector.normalization.max-attempts:5}") int maxAttempts) {
    this.handlers = handlers;
    this.rawEvents = rawEvents;
    this.lifecycle = lifecycle;
    this.maxAttempts = maxAttempts;
  }

  /** Whether any handler supports this kind of event. */
  boolean hasHandler(String provider, String eventType) {
    return handlers.stream().anyMatch(h -> h.supports(provider, eventType));
  }

  @ApplicationModuleListener
  public void on(RawEventStored event) {
    Optional<RawEventHandler> handler =
        handlers.stream().filter(h -> h.supports(event.provider(), event.eventType())).findFirst();
    if (handler.isEmpty()) {
      return;
    }
    locks.run(event.orderingKey(), () -> process(event, handler.get()));
  }

  private void process(RawEventStored event, RawEventHandler handler) {
    try {
      Optional<RawEvent> raw = rawEvents.findById(event.rawEventId());
      if (raw.isEmpty()) {
        log.warn("raw event {} not found, skipped", event.rawEventId());
        return;
      }
      handler.handle(raw.get());
      lifecycle.markProcessed(event.rawEventId());
    } catch (RuntimeException e) {
      boolean dead =
          lifecycle.recordFailure(
              event.rawEventId(),
              e.getClass().getSimpleName() + ": " + e.getMessage(),
              maxAttempts);
      if (dead) {
        log.error(
            "raw event {} ({} {}) is DEAD after {} attempts",
            event.rawEventId(),
            event.provider(),
            event.eventType(),
            maxAttempts,
            e);
        return;
      }
      log.warn("raw event {} failed, will be retried: {}", event.rawEventId(), e.toString());
      throw e;
    }
  }
}
