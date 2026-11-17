package io.cmdb.collector.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * package-private: used only by {@link JpaK8sEventStore}. The upsert is native SQL; the caller must
 * be in a read-write transaction.
 */
interface SpringDataK8sEventRepository extends JpaRepository<K8sEventEntity, Long> {

  /**
   * The cluster and (when already known) the involved resource are resolved by joins; an unknown
   * cluster inserts nothing. On conflict only a newer observation overwrites, but an already
   * resolved resource / service link is never cleared.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO k8s_events
            (cluster_id, event_uid, namespace, involved_kind, involved_name, involved_uid,
             k8s_resource_id, service_id, event_type, reason, message, event_count,
             first_seen_at, last_seen_at, observed_at)
          SELECT c.id, :uid, CAST(:ns AS text), CAST(:kind AS text), CAST(:name AS text),
                 CAST(:involvedUid AS text), r.id, r.service_id, CAST(:type AS text),
                 CAST(:reason AS text), CAST(:message AS text), :count,
                 CAST(:firstSeen AS timestamptz), CAST(:lastSeen AS timestamptz), :observedAt
            FROM clusters c
            LEFT JOIN k8s_resources r
              ON r.cluster_id = c.id AND r.resource_uid = CAST(:involvedUid AS text)
           WHERE c.external_id = :clusterId
          ON CONFLICT (cluster_id, event_uid) DO UPDATE SET
            namespace = EXCLUDED.namespace, involved_kind = EXCLUDED.involved_kind,
            involved_name = EXCLUDED.involved_name, involved_uid = EXCLUDED.involved_uid,
            k8s_resource_id = COALESCE(EXCLUDED.k8s_resource_id, k8s_events.k8s_resource_id),
            service_id = COALESCE(EXCLUDED.service_id, k8s_events.service_id),
            event_type = EXCLUDED.event_type, reason = EXCLUDED.reason,
            message = EXCLUDED.message, event_count = EXCLUDED.event_count,
            first_seen_at = COALESCE(k8s_events.first_seen_at, EXCLUDED.first_seen_at),
            last_seen_at = EXCLUDED.last_seen_at, observed_at = EXCLUDED.observed_at
          WHERE k8s_events.observed_at <= EXCLUDED.observed_at
          """,
      nativeQuery = true)
  int upsertIfNewer(
      @Param("clusterId") String clusterExternalId,
      @Param("uid") String uid,
      @Param("ns") String namespace,
      @Param("kind") String involvedKind,
      @Param("name") String involvedName,
      @Param("involvedUid") String involvedUid,
      @Param("type") String eventType,
      @Param("reason") String reason,
      @Param("message") String message,
      @Param("count") int count,
      @Param("firstSeen") Instant firstSeen,
      @Param("lastSeen") Instant lastSeen,
      @Param("observedAt") Instant observedAt);
}
