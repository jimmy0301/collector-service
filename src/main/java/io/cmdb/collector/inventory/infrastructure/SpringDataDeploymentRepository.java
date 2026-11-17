package io.cmdb.collector.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private: used only by {@link JpaDeploymentStore}. Native SQL, read-write tx. */
interface SpringDataDeploymentRepository extends JpaRepository<DeploymentEntity, Long> {

  /**
   * trigger_type is ARGOCD: an Argo sync that is not (yet) matched to a release. service_id and
   * runtime_target_id come from the Application. A finished operation is never changed; SUPERSEDED
   * is replaced only by a finish seen late.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO deployments
            (external_operation_key, argo_application_id, service_id, runtime_target_id,
             trigger_type, git_revision, sync_status, health_status, deployment_status,
             status_message, started_at, finished_at, metadata)
          VALUES (:key, :appId,
                  (SELECT service_id FROM argo_applications WHERE id = :appId),
                  (SELECT runtime_target_id FROM argo_applications WHERE id = :appId),
                  'ARGOCD', CAST(:revision AS text), CAST(:syncStatus AS text),
                  CAST(:healthStatus AS text), :status, CAST(:message AS text), :startedAt,
                  CAST(:finishedAt AS timestamptz), CAST(:metadata AS jsonb))
          ON CONFLICT (external_operation_key) DO UPDATE SET
            git_revision = COALESCE(EXCLUDED.git_revision, deployments.git_revision),
            sync_status = EXCLUDED.sync_status, health_status = EXCLUDED.health_status,
            deployment_status = EXCLUDED.deployment_status,
            status_message = EXCLUDED.status_message,
            finished_at = EXCLUDED.finished_at, metadata = EXCLUDED.metadata,
            service_id = COALESCE(deployments.service_id, EXCLUDED.service_id),
            runtime_target_id = COALESCE(deployments.runtime_target_id, EXCLUDED.runtime_target_id)
          WHERE deployments.deployment_status = 'RUNNING'
             OR (deployments.deployment_status = 'SUPERSEDED'
                 AND EXCLUDED.deployment_status IN ('SUCCEEDED', 'FAILED'))
          """,
      nativeQuery = true)
  int upsert(
      @Param("key") String operationKey,
      @Param("appId") long applicationId,
      @Param("revision") String gitRevision,
      @Param("syncStatus") String syncStatus,
      @Param("healthStatus") String healthStatus,
      @Param("status") String status,
      @Param("message") String statusMessage,
      @Param("startedAt") Instant startedAt,
      @Param("finishedAt") Instant finishedAt,
      @Param("metadata") String metadata);

  /** A RUNNING deployment that has a newer one of the same Application is SUPERSEDED. */
  @Modifying
  @Query(
      value =
          """
          UPDATE deployments SET deployment_status = 'SUPERSEDED'
           WHERE argo_application_id = :appId AND deployment_status = 'RUNNING'
             AND EXISTS (SELECT 1 FROM deployments newer
                          WHERE newer.argo_application_id = deployments.argo_application_id
                            AND newer.started_at > deployments.started_at)
          """,
      nativeQuery = true)
  int supersedeOlderRunning(@Param("appId") long applicationId);
}
