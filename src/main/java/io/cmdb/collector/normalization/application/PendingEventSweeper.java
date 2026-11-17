package io.cmdb.collector.normalization.application;

import io.cmdb.collector.rawevent.domain.PendingKind;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventRepository;
import io.cmdb.collector.rawevent.domain.RawEventStored;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submits raw events that were landed while no handler supported them (they stay PENDING and leave
 * no publication behind) once a handler exists, for example after a new version is deployed.
 *
 * <p>Only events older than {@code minAge} are taken, so events that are being processed right now
 * are left alone; a duplicate submission would be harmless anyway (handlers are idempotent and
 * events with the same ordering key are serialized). Only kinds that have a handler are touched.
 */
@Service
public class PendingEventSweeper {

  private static final Logger log = LoggerFactory.getLogger(PendingEventSweeper.class);

  private final RawEventRepository rawEvents;
  private final RawEventNormalizer normalizer;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  PendingEventSweeper(
      RawEventRepository rawEvents,
      RawEventNormalizer normalizer,
      ApplicationEventPublisher events,
      Clock clock) {
    this.rawEvents = rawEvents;
    this.normalizer = normalizer;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Publishes {@link RawEventStored} for up to {@code batchSize} PENDING events of each kind that
   * has a handler. Runs in a transaction so the publications are persisted (transactional outbox)
   * and listeners start after commit.
   *
   * @return how many events were submitted
   */
  @Transactional
  public int sweep(Duration minAge, int batchSize) {
    Instant cutoff = clock.instant().minus(minAge);
    int submitted = 0;
    for (PendingKind kind : rawEvents.pendingKinds(cutoff)) {
      if (!normalizer.hasHandler(kind.provider(), kind.eventType())) {
        continue;
      }
      for (RawEvent raw :
          rawEvents.findPending(kind.provider(), kind.eventType(), cutoff, batchSize)) {
        events.publishEvent(
            new RawEventStored(raw.id(), raw.provider(), raw.eventType(), raw.orderingKey()));
        submitted++;
      }
    }
    if (submitted > 0) {
      log.info("submitted {} PENDING raw events that have a handler now", submitted);
    }
    return submitted;
  }
}
