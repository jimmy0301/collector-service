package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.k8s.K8sEventSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sEventStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaK8sEventStore implements K8sEventStore {

  private final SpringDataK8sEventRepository events;

  JpaK8sEventStore(SpringDataK8sEventRepository events) {
    this.events = events;
  }

  @Override
  @Transactional
  public void upsert(K8sEventSnapshot e) {
    events.upsertIfNewer(
        e.clusterExternalId(),
        e.eventUid(),
        e.namespace(),
        e.involvedKind(),
        e.involvedName(),
        e.involvedUid(),
        e.eventType(),
        e.reason(),
        e.message(),
        e.count(),
        e.firstSeenAt(),
        e.lastSeenAt(),
        e.observedAt());
  }
}
