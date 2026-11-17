package io.cmdb.collector.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * package-private: used only by {@link JpaK8sResourceHistoryStore} and {@link JpaK8sResourceStore}.
 * Native SQL; the caller must be in a read-write transaction.
 *
 * <p>Recording is two statements ({@link #closeIfChanged}, then {@link #startIfNoCurrent}) instead
 * of one CTE, because the unique "one current row" index would otherwise be checked before the old
 * row is closed.
 */
interface SpringDataK8sResourceHistoryRepository
    extends JpaRepository<K8sResourceHistoryEntity, Long> {

  /** Closes the current row when the state differs and the observation is newer than the row. */
  @Modifying
  @Query(
      value =
          """
          UPDATE k8s_resource_history SET valid_to = :at
           WHERE k8s_resource_id = :resourceId AND valid_to IS NULL AND valid_from < :at
             AND (phase, restart_count, replicas, ready_replicas, images, terminating)
                 IS DISTINCT FROM
                 (CAST(:phase AS varchar), :restartCount, CAST(:replicas AS integer),
                  CAST(:readyReplicas AS integer), CAST(:images AS jsonb), :terminating)
          """,
      nativeQuery = true)
  int closeIfChanged(
      @Param("resourceId") long resourceId,
      @Param("phase") String phase,
      @Param("restartCount") int restartCount,
      @Param("replicas") Integer replicas,
      @Param("readyReplicas") Integer readyReplicas,
      @Param("images") String images,
      @Param("terminating") boolean terminating,
      @Param("at") Instant at);

  /**
   * Starts a row unless a row still covers {@code at} or later: that is the current row (same state
   * or older observation) or a closed range that already includes it, so history is never
   * rewritten.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO k8s_resource_history
            (k8s_resource_id, valid_from, phase, restart_count, replicas, ready_replicas, images,
             terminating)
          SELECT :resourceId, :at, CAST(:phase AS varchar), :restartCount,
                 CAST(:replicas AS integer), CAST(:readyReplicas AS integer),
                 CAST(:images AS jsonb), :terminating
           WHERE NOT EXISTS (
                 SELECT 1 FROM k8s_resource_history
                  WHERE k8s_resource_id = :resourceId
                    AND COALESCE(valid_to, 'infinity'::timestamptz) > :at)
          """,
      nativeQuery = true)
  int startIfNoCurrent(
      @Param("resourceId") long resourceId,
      @Param("phase") String phase,
      @Param("restartCount") int restartCount,
      @Param("replicas") Integer replicas,
      @Param("readyReplicas") Integer readyReplicas,
      @Param("images") String images,
      @Param("terminating") boolean terminating,
      @Param("at") Instant at);

  /** Closes the current rows of the resources that were just marked deleted at {@code at}. */
  @Modifying
  @Query(
      value =
          """
          UPDATE k8s_resource_history SET valid_to = :at
           WHERE valid_to IS NULL AND valid_from < :at
             AND k8s_resource_id IN (
                 SELECT id FROM k8s_resources
                  WHERE cluster_id = (SELECT id FROM clusters WHERE external_id = :clusterId)
                    AND namespace = :ns AND resource_kind = :kind AND deleted_at = :at)
          """,
      nativeQuery = true)
  int closeDeleted(
      @Param("clusterId") String clusterExternalId,
      @Param("ns") String namespace,
      @Param("kind") String kind,
      @Param("at") Instant at);
}
