package io.cmdb.collector.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * package-private: used only by {@link JpaK8sResourceStore}. Writes are native SQL; the caller must
 * be in a read-write transaction.
 *
 * <p>A statement with {@code RETURNING} must not be marked {@code @Modifying} (it has to run as a
 * query to return a result).
 */
interface SpringDataK8sResourceRepository extends JpaRepository<K8sResourceEntity, Long> {

  /**
   * Overwrites only when the data is newer; when the condition fails there is no RETURNING row and
   * null is returned.
   */
  @Query(
      value =
          """
          INSERT INTO k8s_resources
            (cluster_id, resource_uid, namespace, resource_kind, resource_name, api_version,
             restart_count, phase, labels, annotations, spec, status, observed_at, deleted_at)
          VALUES ((SELECT id FROM clusters WHERE external_id = :clusterId), :uid,
                  CAST(:ns AS text), :kind, :name, CAST(:apiVersion AS text),
                  :restartCount, CAST(:phase AS text), CAST(:labels AS jsonb),
                  CAST(:annotations AS jsonb), CAST(:spec AS jsonb), CAST(:status AS jsonb),
                  :observedAt, CAST(:deletedAt AS timestamptz))
          ON CONFLICT (cluster_id, resource_uid) DO UPDATE SET
            namespace = EXCLUDED.namespace, resource_kind = EXCLUDED.resource_kind,
            resource_name = EXCLUDED.resource_name, api_version = EXCLUDED.api_version,
            restart_count = EXCLUDED.restart_count, phase = EXCLUDED.phase,
            labels = EXCLUDED.labels, annotations = EXCLUDED.annotations,
            spec = EXCLUDED.spec, status = EXCLUDED.status,
            observed_at = EXCLUDED.observed_at, deleted_at = EXCLUDED.deleted_at
          WHERE k8s_resources.observed_at <= EXCLUDED.observed_at
          RETURNING id
          """,
      nativeQuery = true)
  Long upsertIfNewer(
      @Param("clusterId") String clusterExternalId,
      @Param("uid") String uid,
      @Param("ns") String namespace,
      @Param("kind") String kind,
      @Param("name") String name,
      @Param("apiVersion") String apiVersion,
      @Param("restartCount") int restartCount,
      @Param("phase") String phase,
      @Param("labels") String labels,
      @Param("annotations") String annotations,
      @Param("spec") String spec,
      @Param("status") String status,
      @Param("observedAt") Instant observedAt,
      @Param("deletedAt") Instant deletedAt);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO k8s_resources
            (cluster_id, resource_uid, namespace, resource_kind, resource_name, observed_at)
          VALUES ((SELECT id FROM clusters WHERE external_id = :clusterId), :uid,
                  CAST(:ns AS text), :kind, :name, :epoch)
          ON CONFLICT (cluster_id, resource_uid) DO NOTHING
          """,
      nativeQuery = true)
  int insertStubIfAbsent(
      @Param("clusterId") String clusterExternalId,
      @Param("uid") String uid,
      @Param("ns") String namespace,
      @Param("kind") String kind,
      @Param("name") String name,
      @Param("epoch") Instant epoch);

  @Query(
      value =
          """
          SELECT id FROM k8s_resources
           WHERE cluster_id = (SELECT id FROM clusters WHERE external_id = :clusterId)
             AND resource_uid = :uid
          """,
      nativeQuery = true)
  Long findId(@Param("clusterId") String clusterExternalId, @Param("uid") String uid);

  /**
   * uids are passed as JSON array text (an empty array is also safe, it never produces NOT IN ()).
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE k8s_resources SET deleted_at = :at
           WHERE cluster_id = (SELECT id FROM clusters WHERE external_id = :clusterId)
             AND namespace = :ns AND resource_kind = :kind
             AND deleted_at IS NULL AND observed_at <= :at
             AND resource_uid NOT IN (SELECT jsonb_array_elements_text(CAST(:uids AS jsonb)))
          """,
      nativeQuery = true)
  int markMissingDeleted(
      @Param("clusterId") String clusterExternalId,
      @Param("ns") String namespace,
      @Param("kind") String kind,
      @Param("uids") String uidsJson,
      @Param("at") Instant at);
}
