package io.cmdb.collector.rawevent.infrastructure;

import io.cmdb.collector.rawevent.domain.RawEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * package-private: Spring Data is invisible outside; access goes only through the domain's
 * RawEventRepository.
 */
interface SpringDataJpaRawEventRepository extends JpaRepository<RawEvent, Long> {

  Optional<RawEvent> findByProviderAndExternalEventId(String provider, String externalEventId);

  /**
   * Atomic idempotent write: returns the new id on insert; if it already exists (unique key
   * conflict) there is no row and null is returned. The other columns (status, timestamps) use
   * database defaults. Not {@code @Modifying}: INSERT ... RETURNING must run as a query to return a
   * result; the caller must be in a read-write transaction.
   */
  @Query(
      value =
          """
          INSERT INTO integration_raw_events
            (provider, event_type, external_event_id, ordering_key, payload)
          VALUES (:provider, :eventType, :externalEventId, :orderingKey, CAST(:payload AS jsonb))
          ON CONFLICT (provider, external_event_id) DO NOTHING
          RETURNING id
          """,
      nativeQuery = true)
  Long insertIfAbsent(
      @Param("provider") String provider,
      @Param("eventType") String eventType,
      @Param("externalEventId") String externalEventId,
      @Param("orderingKey") String orderingKey,
      @Param("payload") String payload);

  @Modifying
  @Query(
      value =
          """
          UPDATE integration_raw_events
             SET process_status = 'PROCESSED', processed_at = now(), last_error = NULL
           WHERE id = :id
          """,
      nativeQuery = true)
  int markProcessed(@Param("id") Long id);

  @Query(
      value =
          """
          UPDATE integration_raw_events
             SET process_status = 'FAILED', attempt_count = attempt_count + 1, last_error = :error
           WHERE id = :id
          RETURNING attempt_count
          """,
      nativeQuery = true)
  Integer recordFailure(@Param("id") Long id, @Param("error") String error);

  @Modifying
  @Query(
      value =
          """
          UPDATE integration_raw_events
             SET process_status = 'DEAD', last_error = :error
           WHERE id = :id
          """,
      nativeQuery = true)
  int markDead(@Param("id") Long id, @Param("error") String error);

  /** Projection of {@link #pendingKinds}. */
  interface Kind {
    String getProvider();

    String getEventType();
  }

  @Query(
      value =
          """
          SELECT DISTINCT provider AS provider, event_type AS eventType
            FROM integration_raw_events
           WHERE process_status IN ('PENDING', 'FAILED') AND process_status = 'PENDING'
             AND received_at < :before
          """,
      nativeQuery = true)
  List<Kind> pendingKinds(@Param("before") Instant before);

  @Query(
      value =
          """
          SELECT id FROM integration_raw_events
           WHERE process_status IN ('PENDING', 'FAILED') AND process_status = 'PENDING'
             AND provider = :provider AND event_type = :eventType AND received_at < :before
           ORDER BY id LIMIT :limit
          """,
      nativeQuery = true)
  List<Long> findPendingIds(
      @Param("provider") String provider,
      @Param("eventType") String eventType,
      @Param("before") Instant before,
      @Param("limit") int limit);
}
