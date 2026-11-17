package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Column;
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

/** Row mapping of k8s_resources (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "k8s_resources")
class K8sResourceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long clusterId;
  private Long serviceId;
  private Long runtimeTargetId;
  private Long argoApplicationId;
  private String resourceUid;
  private String namespace;
  private String resourceKind;
  private String resourceName;
  private String apiVersion;
  private int restartCount;
  private String phase;
  private String argoHealthStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode labels;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode annotations;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode spec;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode status;

  /** A database generated column (labels ->> 'cmdb.io/service-id'). */
  @Column(insertable = false, updatable = false)
  private String cmdbServiceCode;

  private Instant observedAt;
  private Instant deletedAt;

  protected K8sResourceEntity() {}
}
