package io.cmdb.collector.rawevent.infrastructure;

import io.cmdb.collector.rawevent.domain.PendingKind;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaRawEventRepository implements RawEventRepository {

  private final SpringDataJpaRawEventRepository springData;

  JpaRawEventRepository(SpringDataJpaRawEventRepository springData) {
    this.springData = springData;
  }

  @Override
  @Transactional
  public Optional<Long> saveIfAbsent(RawEvent event) {
    return Optional.ofNullable(
        springData.insertIfAbsent(
            event.provider(),
            event.eventType(),
            event.externalEventId(),
            event.orderingKey(),
            event.payload().toString()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RawEvent> findById(Long id) {
    return springData.findById(id);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RawEvent> findByProviderAndExternalEventId(
      String provider, String externalEventId) {
    return springData.findByProviderAndExternalEventId(provider, externalEventId);
  }

  @Override
  @Transactional
  public void markProcessed(Long id) {
    springData.markProcessed(id);
  }

  @Override
  @Transactional
  public int recordFailure(Long id, String error) {
    Integer attempts = springData.recordFailure(id, error);
    return attempts == null ? 0 : attempts;
  }

  @Override
  @Transactional
  public void markDead(Long id, String error) {
    springData.markDead(id, error);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PendingKind> pendingKinds(Instant receivedBefore) {
    return springData.pendingKinds(receivedBefore).stream()
        .map(k -> new PendingKind(k.getProvider(), k.getEventType()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<RawEvent> findPending(
      String provider, String eventType, Instant receivedBefore, int limit) {
    List<Long> ids = springData.findPendingIds(provider, eventType, receivedBefore, limit);
    return springData.findAllById(ids).stream().sorted(Comparator.comparing(RawEvent::id)).toList();
  }
}
