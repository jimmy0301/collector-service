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

/** Row mapping of deployments (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "deployments")
class DeploymentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String deploymentKey;
  private String externalOperationKey;
  private Long serviceId;
  private Long runtimeTargetId;
  private Long buildId;
  private Long releaseDeploymentId;
  private Long argoApplicationId;
  private String triggerType;
  private String gitRevision;
  private String applicationRevision;
  private String syncStatus;
  private String healthStatus;
  private String deploymentStatus;
  private String statusMessage;
  private Instant startedAt;
  private Instant finishedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode metadata;

  private Instant createdAt;

  protected DeploymentEntity() {}
}
