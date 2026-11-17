package io.cmdb.collector.rawevent.application;

import io.cmdb.collector.rawevent.domain.PayloadRedactor;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventRepository;
import io.cmdb.collector.rawevent.domain.RawEventStored;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The collector's only write entry point: redact → deduplicate → land → publish {@link
 * RawEventStored}.
 *
 * <p>Every poll sees many unchanged objects, so an in-memory LRU first filters out recently seen
 * event IDs to avoid hitting the DB unique key for each one; the database's {@code uk_raw_event} is
 * the final idempotency guarantee (restarts and multiple instances rely on it).
 */
@Service
public class RawEventRecorder {

  private final RawEventRepository repository;
  private final ApplicationEventPublisher events;
  private final MeterRegistry meters;
  private final Map<String, Boolean> recentlySeen;

  public RawEventRecorder(
      RawEventRepository repository,
      ApplicationEventPublisher events,
      MeterRegistry meters,
      @Value("${collector.dedupe-cache-size:50000}") int cacheSize) {
    this.repository = repository;
    this.events = events;
    this.meters = meters;
    this.recentlySeen =
        Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > cacheSize;
              }
            });
  }

  /**
   * Redacts, deduplicates, lands the event and publishes {@link RawEventStored}.
   *
   * <p>Must run inside a transaction: the raw event INSERT and the event publication commit in the
   * same transaction (transactional outbox), and listeners run only after commit.
   *
   * @return true if this is a new event (not landed before)
   */
  @Transactional
  public boolean record(RawEvent event) {
    String key = event.provider() + '|' + event.externalEventId();
    if (recentlySeen.containsKey(key)) {
      count(event, "duplicate");
      return false;
    }
    Optional<Long> id =
        repository.saveIfAbsent(event.withPayload(PayloadRedactor.redact(event.payload())));
    recentlySeen.put(key, Boolean.TRUE);
    id.ifPresent(
        newId ->
            events.publishEvent(
                new RawEventStored(
                    newId, event.provider(), event.eventType(), event.orderingKey())));
    count(event, id.isPresent() ? "inserted" : "duplicate");
    return id.isPresent();
  }

  private void count(RawEvent event, String result) {
    meters
        .counter("collector.raw.events", "provider", event.provider(), "result", result)
        .increment();
  }
}
