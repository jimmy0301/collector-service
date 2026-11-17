package io.cmdb.collector.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private: used only by {@link JpaArgoApplicationStore}. Native SQL, read-write tx. */
interface SpringDataArgoApplicationRepository extends JpaRepository<ArgoApplicationEntity, Long> {

  /**
   * Overwrites only when the observation is not older than the stored one (a stub has no
   * observed_at and is always overwritten). The cluster is matched within the site, by Argo server
   * URL (case and trailing slash ignored) or by Argo cluster name.
   */
  @Query(
      value =
          """
          INSERT INTO argo_applications
            (argo_server, external_uid, name, project, destination_server, destination_name,
             destination_namespace, sources, sync_status, health_status, auto_sync,
             sync_revisions, observed_at, cluster_id)
          VALUES (:site, :uid, :name, CAST(:project AS text), CAST(:destServer AS text),
                  CAST(:destName AS text), CAST(:destNamespace AS text),
                  CAST(:sources AS jsonb), CAST(:syncStatus AS text),
                  CAST(:healthStatus AS text), :autoSync, CAST(:revisions AS jsonb),
                  :observedAt,
                  (SELECT c.id FROM clusters c JOIN sites s ON s.id = c.site_id
                    WHERE s.code = :site
                      AND ((CAST(:destServer AS text) IS NOT NULL
                            AND lower(rtrim(c.argo_server_url, '/'))
                                = lower(rtrim(CAST(:destServer AS text), '/')))
                        OR (CAST(:destName AS text) IS NOT NULL
                            AND c.argo_cluster_name = CAST(:destName AS text)))
                    ORDER BY c.id LIMIT 1))
          ON CONFLICT (external_uid) DO UPDATE SET
            argo_server = EXCLUDED.argo_server, name = EXCLUDED.name,
            project = EXCLUDED.project, destination_server = EXCLUDED.destination_server,
            destination_name = EXCLUDED.destination_name,
            destination_namespace = EXCLUDED.destination_namespace,
            sources = EXCLUDED.sources, sync_status = EXCLUDED.sync_status,
            health_status = EXCLUDED.health_status, auto_sync = EXCLUDED.auto_sync,
            sync_revisions = EXCLUDED.sync_revisions, observed_at = EXCLUDED.observed_at,
            cluster_id = COALESCE(EXCLUDED.cluster_id, argo_applications.cluster_id)
          WHERE argo_applications.observed_at IS NULL
             OR argo_applications.observed_at <= EXCLUDED.observed_at
          RETURNING id
          """,
      nativeQuery = true)
  Long upsertIfNewer(
      @Param("site") String site,
      @Param("uid") String uid,
      @Param("name") String name,
      @Param("project") String project,
      @Param("destServer") String destinationServer,
      @Param("destName") String destinationName,
      @Param("destNamespace") String destinationNamespace,
      @Param("sources") String sources,
      @Param("syncStatus") String syncStatus,
      @Param("healthStatus") String healthStatus,
      @Param("autoSync") boolean autoSync,
      @Param("revisions") String syncRevisions,
      @Param("observedAt") Instant observedAt);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO argo_applications (argo_server, external_uid, name)
          VALUES (:site, :uid, :name)
          ON CONFLICT (external_uid) DO NOTHING
          """,
      nativeQuery = true)
  int insertStubIfAbsent(
      @Param("site") String site, @Param("uid") String uid, @Param("name") String name);

  @Query(value = "SELECT id FROM argo_applications WHERE external_uid = :uid", nativeQuery = true)
  Long findId(@Param("uid") String uid);

  /**
   * nodes is a JSON array of {uid, kind, namespace, name, health}. Creates the placeholder rows of
   * the collected kinds when the Application's cluster is known, and updates the Argo columns of
   * rows that already exist. Only rows that would change are written.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO k8s_resources
            (cluster_id, resource_uid, namespace, resource_kind, resource_name, observed_at,
             argo_application_id, argo_health_status)
          SELECT a.cluster_id, n.uid, n.namespace, n.kind, n.name, :epoch, a.id, n.health
            FROM argo_applications a,
                 jsonb_to_recordset(CAST(:nodes AS jsonb))
                   AS n(uid text, kind text, namespace text, name text, health text)
           WHERE a.id = :appId AND a.cluster_id IS NOT NULL
             AND n.kind IN ('Deployment', 'ReplicaSet', 'Pod') AND n.name IS NOT NULL
          ON CONFLICT (cluster_id, resource_uid) DO UPDATE SET
            argo_application_id = EXCLUDED.argo_application_id,
            argo_health_status = EXCLUDED.argo_health_status
          WHERE k8s_resources.argo_application_id IS DISTINCT FROM EXCLUDED.argo_application_id
             OR k8s_resources.argo_health_status IS DISTINCT FROM EXCLUDED.argo_health_status
          """,
      nativeQuery = true)
  int upsertManagedResources(
      @Param("appId") long applicationId,
      @Param("nodes") String nodes,
      @Param("epoch") Instant epoch);

  /** Catch-all for an Application whose cluster is not known: match by uid alone. */
  @Modifying
  @Query(
      value =
          """
          UPDATE k8s_resources r
             SET argo_application_id = :appId, argo_health_status = n.health
            FROM jsonb_to_recordset(CAST(:nodes AS jsonb)) AS n(uid text, health text),
                 argo_applications a
           WHERE a.id = :appId AND a.cluster_id IS NULL AND r.resource_uid = n.uid
             AND (r.argo_application_id IS DISTINCT FROM :appId
               OR r.argo_health_status IS DISTINCT FROM n.health)
          """,
      nativeQuery = true)
  int linkResourcesByUid(@Param("appId") long applicationId, @Param("nodes") String nodes);

  @Modifying
  @Query(
      value =
          """
          UPDATE argo_applications a SET cluster_id = c.id
            FROM clusters c JOIN sites s ON s.id = c.site_id
           WHERE a.cluster_id IS NULL AND a.argo_server = :site AND s.code = :site
             AND ((a.destination_server IS NOT NULL
                   AND lower(rtrim(c.argo_server_url, '/'))
                       = lower(rtrim(a.destination_server, '/')))
               OR (a.destination_name IS NOT NULL AND c.argo_cluster_name = a.destination_name))
          """,
      nativeQuery = true)
  int linkUnmappedClusters(@Param("site") String site);
}
