package io.cmdb.collector.syncrun.infrastructure;

import io.cmdb.collector.syncrun.domain.SyncRun;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private: Spring Data is invisible outside. */
interface SpringDataJpaSyncRunRepository extends JpaRepository<SyncRun, Long> {

  /**
   * JPQL DELETE has no LIMIT and Postgres has no DELETE ... LIMIT either, so a subquery limits the
   * rows per batch.
   */
  @Modifying
  @Query(
      value =
          """
          DELETE FROM integration_sync_runs
           WHERE id IN (SELECT id FROM integration_sync_runs
                         WHERE started_at < :cutoff
                         LIMIT :batchSize)
          """,
      nativeQuery = true)
  int deleteStartedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
