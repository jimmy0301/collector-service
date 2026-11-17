package io.cmdb.collector.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private: used only by {@link JpaReleaseStore}. Native SQL, read-write transaction. */
interface SpringDataReleaseRepository extends JpaRepository<ReleaseEntity, Long> {

  /**
   * Overwrites only when the observation is not older than the stored one (a stub has no
   * modified_at and is always overwritten). A null never blanks a stored value; pipeline_id is
   * resolved from a registered release definition.
   */
  @Query(
      value =
          """
          INSERT INTO releases
            (project, external_release_id, external_definition_id, definition_name, name, status,
             reason, artifact_alias, artifact_build_ref, source_branch, artifacts, created_by,
             created_at, modified_at, pipeline_id)
          VALUES (:project, :releaseId, :definitionId, CAST(:definitionName AS text),
                  CAST(:name AS text), CAST(:status AS text), CAST(:reason AS text),
                  CAST(:alias AS text), CAST(:buildRef AS text), CAST(:branch AS text),
                  CAST(:artifacts AS jsonb), CAST(:createdBy AS text),
                  CAST(:createdAt AS timestamptz), CAST(:modifiedAt AS timestamptz),
                  (SELECT id FROM pipelines
                    WHERE provider = 'AZURE_DEVOPS_RELEASE' AND external_id = :definitionId))
          ON CONFLICT (project, external_release_id) DO UPDATE SET
            external_definition_id = EXCLUDED.external_definition_id,
            definition_name = COALESCE(EXCLUDED.definition_name, releases.definition_name),
            name = COALESCE(EXCLUDED.name, releases.name),
            status = COALESCE(EXCLUDED.status, releases.status),
            reason = COALESCE(EXCLUDED.reason, releases.reason),
            artifact_alias = COALESCE(EXCLUDED.artifact_alias, releases.artifact_alias),
            artifact_build_ref = COALESCE(EXCLUDED.artifact_build_ref, releases.artifact_build_ref),
            source_branch = COALESCE(EXCLUDED.source_branch, releases.source_branch),
            artifacts = COALESCE(EXCLUDED.artifacts, releases.artifacts),
            created_by = COALESCE(EXCLUDED.created_by, releases.created_by),
            created_at = COALESCE(EXCLUDED.created_at, releases.created_at),
            modified_at = EXCLUDED.modified_at,
            pipeline_id = COALESCE(releases.pipeline_id, EXCLUDED.pipeline_id)
          WHERE releases.modified_at IS NULL OR releases.modified_at <= EXCLUDED.modified_at
          RETURNING id
          """,
      nativeQuery = true)
  Long upsertIfNewer(
      @Param("project") String project,
      @Param("releaseId") String externalReleaseId,
      @Param("definitionId") String definitionId,
      @Param("definitionName") String definitionName,
      @Param("name") String name,
      @Param("status") String status,
      @Param("reason") String reason,
      @Param("alias") String artifactAlias,
      @Param("buildRef") String artifactBuildRef,
      @Param("branch") String sourceBranch,
      @Param("artifacts") String artifacts,
      @Param("createdBy") String createdBy,
      @Param("createdAt") Instant createdAt,
      @Param("modifiedAt") Instant modifiedAt);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO releases
            (project, external_release_id, external_definition_id, definition_name, name,
             pipeline_id)
          VALUES (:project, :releaseId, :definitionId, CAST(:definitionName AS text),
                  CAST(:name AS text),
                  (SELECT id FROM pipelines
                    WHERE provider = 'AZURE_DEVOPS_RELEASE' AND external_id = :definitionId))
          ON CONFLICT (project, external_release_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertStubIfAbsent(
      @Param("project") String project,
      @Param("releaseId") String externalReleaseId,
      @Param("definitionId") String definitionId,
      @Param("definitionName") String definitionName,
      @Param("name") String name);

  @Query(
      value =
          "SELECT id FROM releases WHERE project = :project AND external_release_id = :releaseId",
      nativeQuery = true)
  Long findId(@Param("project") String project, @Param("releaseId") String externalReleaseId);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO release_deployments
            (release_id, external_release_env_id, definition_environment_id, environment_name,
             attempt, external_deployment_id, deployment_status, operation_status, reason,
             requested_by, queued_at, started_at, completed_at, last_modified_at,
             runtime_target_id)
          VALUES (:releaseId, :envId, CAST(:defEnvId AS text), :envName, :attempt,
                  CAST(:deploymentId AS text), CAST(:deploymentStatus AS text),
                  CAST(:operationStatus AS text), CAST(:reason AS text),
                  CAST(:requestedBy AS text), CAST(:queuedAt AS timestamptz),
                  CAST(:startedAt AS timestamptz), CAST(:completedAt AS timestamptz),
                  CAST(:modifiedAt AS timestamptz),
                  (SELECT min(id) FROM runtime_targets
                    WHERE release_definition_env_id = CAST(:defEnvId AS text)
                   HAVING count(*) = 1))
          ON CONFLICT (release_id, external_release_env_id, attempt) DO UPDATE SET
            definition_environment_id = EXCLUDED.definition_environment_id,
            environment_name = EXCLUDED.environment_name,
            external_deployment_id = EXCLUDED.external_deployment_id,
            deployment_status = EXCLUDED.deployment_status,
            operation_status = EXCLUDED.operation_status,
            reason = EXCLUDED.reason,
            requested_by = COALESCE(EXCLUDED.requested_by, release_deployments.requested_by),
            queued_at = EXCLUDED.queued_at, started_at = EXCLUDED.started_at,
            completed_at = EXCLUDED.completed_at, last_modified_at = EXCLUDED.last_modified_at,
            runtime_target_id =
              COALESCE(release_deployments.runtime_target_id, EXCLUDED.runtime_target_id)
          WHERE release_deployments.last_modified_at IS NULL
             OR release_deployments.last_modified_at <= EXCLUDED.last_modified_at
          """,
      nativeQuery = true)
  int upsertDeploymentIfNewer(
      @Param("releaseId") long releaseId,
      @Param("envId") String externalReleaseEnvId,
      @Param("defEnvId") String definitionEnvironmentId,
      @Param("envName") String environmentName,
      @Param("attempt") int attempt,
      @Param("deploymentId") String externalDeploymentId,
      @Param("deploymentStatus") String deploymentStatus,
      @Param("operationStatus") String operationStatus,
      @Param("reason") String reason,
      @Param("requestedBy") String requestedBy,
      @Param("queuedAt") Instant queuedAt,
      @Param("startedAt") Instant startedAt,
      @Param("completedAt") Instant completedAt,
      @Param("modifiedAt") Instant lastModifiedAt);
}
