package io.cmdb.collector.syncrun.domain;

import java.time.Instant;
import java.util.Optional;
import org.jmolecules.ddd.annotation.Repository;

/** Repository of SyncRun (plain Java, no framework dependency). */
@Repository
public interface SyncRunRepository {

  /** Inserts or updates; after an insert {@link SyncRun#id()} has a value. */
  void save(SyncRun run);

  Optional<SyncRun> findById(Long id);

  /**
   * Deletes records started before the cutoff, at most batchSize rows at a time (small batches, to
   * avoid long transactions).
   */
  int deleteStartedBefore(Instant cutoff, int batchSize);
}
