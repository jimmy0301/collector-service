package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Row mapping of release_deployments (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "release_deployments")
class ReleaseDeploymentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long releaseId;
  private String externalReleaseEnvId;
  private String definitionEnvironmentId;
  private String environmentName;
  private int attempt;
  private String externalDeploymentId;
  private String deploymentStatus;
  private String operationStatus;
  private String reason;
  private String requestedBy;
  private Instant queuedAt;
  private Instant startedAt;
  private Instant completedAt;
  private Instant lastModifiedAt;
  private Long runtimeTargetId;

  protected ReleaseDeploymentEntity() {}
}
