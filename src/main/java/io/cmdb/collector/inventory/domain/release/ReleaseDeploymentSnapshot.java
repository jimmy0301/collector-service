package io.cmdb.collector.inventory.domain.release;

import java.time.Instant;

/**
 * One deployment of a release to one stage: (release, stage instance, attempt). Same meaning as
 * release_deployments in V1__init.sql.
 */
public record ReleaseDeploymentSnapshot(
    long releaseId,
    String externalReleaseEnvId,
    String definitionEnvironmentId,
    String environmentName,
    int attempt,
    String externalDeploymentId,
    String deploymentStatus,
    String operationStatus,
    String reason,
    String requestedBy,
    Instant queuedAt,
    Instant startedAt,
    Instant completedAt,
    Instant lastModifiedAt) {}
