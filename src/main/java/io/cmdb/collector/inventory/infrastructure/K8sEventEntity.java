package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Row mapping of k8s_events (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "k8s_events")
class K8sEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long clusterId;
  private String eventUid;
  private String namespace;
  private String involvedKind;
  private String involvedName;
  private String involvedUid;
  private Long k8sResourceId;
  private Long serviceId;
  private String eventType;
  private String reason;
  private String message;
  private int eventCount;
  private Instant firstSeenAt;
  private Instant lastSeenAt;
  private Instant observedAt;

  protected K8sEventEntity() {}
}
