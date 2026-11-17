package io.cmdb.collector.inventory.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * package-private: used only by {@link JpaClusterStore}. Writes are native SQL; the caller must be
 * in a read-write transaction.
 */
interface SpringDataClusterRepository extends JpaRepository<ClusterEntity, Long> {

  /**
   * Makes the rest of the transaction the only writer of this cluster. clusters has two unique keys
   * (code and external_id) but ON CONFLICT can name only one, so two transactions creating the same
   * cluster at the same moment could fail the loser with a violation of the other key. With the
   * lock the loser waits, then sees the committed row and takes the conflict path. Released at
   * commit or rollback, and it works across several collector instances because it lives in the
   * database.
   */
  @Query(
      value =
          "SELECT CAST(1 AS integer) FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
      nativeQuery = true)
  int lockCluster(@Param("key") String key);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO clusters (code, site_id, provider, external_id, argo_server_url, name)
          VALUES (:code, (SELECT id FROM sites WHERE code = :site), 'ONPREM', :externalId,
                  CAST(:server AS text), :name)
          ON CONFLICT (external_id) DO UPDATE SET
            name = EXCLUDED.name,
            site_id = COALESCE(EXCLUDED.site_id, clusters.site_id),
            argo_server_url = COALESCE(EXCLUDED.argo_server_url, clusters.argo_server_url)
          """,
      nativeQuery = true)
  int upsert(
      @Param("code") String code,
      @Param("site") String site,
      @Param("externalId") String externalId,
      @Param("server") String server,
      @Param("name") String name);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO clusters (code, site_id, provider, external_id, argo_server_url, name)
          VALUES (:code, (SELECT id FROM sites WHERE code = :site), 'ONPREM', :externalId,
                  CAST(:server AS text), :name)
          ON CONFLICT DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("code") String code,
      @Param("site") String site,
      @Param("externalId") String externalId,
      @Param("server") String server,
      @Param("name") String name);

  @Modifying
  @Query(
      value =
          """
          UPDATE clusters SET argo_cluster_name = :name
           WHERE site_id = (SELECT id FROM sites WHERE code = :site)
             AND lower(rtrim(argo_server_url, '/')) = lower(rtrim(:server, '/'))
             AND argo_cluster_name IS DISTINCT FROM :name
          """,
      nativeQuery = true)
  int setArgoClusterName(
      @Param("site") String site, @Param("server") String server, @Param("name") String name);
}
