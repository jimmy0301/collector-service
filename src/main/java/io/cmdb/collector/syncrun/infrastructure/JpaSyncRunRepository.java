package io.cmdb.collector.syncrun.infrastructure;

import io.cmdb.collector.syncrun.domain.SyncRun;
import io.cmdb.collector.syncrun.domain.SyncRunRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaSyncRunRepository implements SyncRunRepository {

  private final SpringDataJpaSyncRunRepository springData;

  JpaSyncRunRepository(SpringDataJpaSyncRunRepository springData) {
    this.springData = springData;
  }

  @Override
  public void save(SyncRun run) {
    springData.save(run);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SyncRun> findById(Long id) {
    return springData.findById(id);
  }

  @Override
  @Transactional
  public int deleteStartedBefore(Instant cutoff, int batchSize) {
    return springData.deleteStartedBefore(cutoff, batchSize);
  }
}
