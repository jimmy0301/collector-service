package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** Row mapping of k8s_resource_history (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "k8s_resource_history")
class K8sResourceHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long k8sResourceId;
  private Instant validFrom;
  private Instant validTo;
  private String phase;
  private int restartCount;
  private Integer replicas;
  private Integer readyReplicas;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode images;

  private boolean terminating;

  protected K8sResourceHistoryEntity() {}
}
