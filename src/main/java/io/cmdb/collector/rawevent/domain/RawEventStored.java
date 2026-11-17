package io.cmdb.collector.rawevent.domain;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * A raw event has been stored (the transaction has committed). The trigger event for normalization:
 * carries only identifying information, not the payload; the listener reads the raw event back by
 * id. The event is persisted by the Spring Modulith event publication registry and written in the
 * same transaction as the raw event INSERT (transactional outbox), so it cannot be lost.
 */
@DomainEvent
public record RawEventStored(
    Long rawEventId, String provider, String eventType, String orderingKey) {}
