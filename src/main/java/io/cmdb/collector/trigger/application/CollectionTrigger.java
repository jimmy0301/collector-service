package io.cmdb.collector.trigger.application;

import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.trigger.domain.CollectionJob;
import io.cmdb.collector.trigger.domain.JobReport;
import io.cmdb.collector.trigger.domain.TriggerBusyException;
import io.cmdb.collector.trigger.domain.TriggerReport;
import io.cmdb.collector.trigger.domain.UnknownJobException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs collect jobs on demand, outside the schedule. A source runs one trigger at a time (a second
 * request gets {@link TriggerBusyException}); a trigger may still overlap with a scheduled poll,
 * which is safe because landing is idempotent (events are deduplicated by their external id).
 * Failures are reported, not thrown, so one failing job does not stop the others.
 */
@Service
public class CollectionTrigger {

  private static final Logger log = LoggerFactory.getLogger(CollectionTrigger.class);
  private static final int MAX_ERROR_LENGTH = 500;

  /** Jobs grouped by source; the order jobs were registered in is the order "all" runs them. */
  private final Map<String, List<CollectionJob>> bySource = new LinkedHashMap<>();

  private final Map<String, ReentrantLock> locks = new LinkedHashMap<>();

  public CollectionTrigger(List<CollectionJob> jobs) {
    for (CollectionJob job : jobs) {
      bySource.computeIfAbsent(job.source(), s -> new ArrayList<>()).add(job);
      locks.computeIfAbsent(job.source(), s -> new ReentrantLock());
    }
  }

  /** Source → job names, in run order. */
  public Map<String, List<String>> available() {
    Map<String, List<String>> result = new LinkedHashMap<>();
    bySource.forEach(
        (source, jobs) -> result.put(source, jobs.stream().map(CollectionJob::name).toList()));
    return result;
  }

  /** Runs every source in registration order (Argo first, so Kubernetes learns its scope). */
  public List<TriggerReport> runAll() {
    List<TriggerReport> reports = new ArrayList<>();
    for (String source : bySource.keySet()) {
      try {
        reports.add(run(source, null));
      } catch (TriggerBusyException busy) {
        reports.add(
            new TriggerReport(
                source,
                List.of(new JobReport("*", SyncStatus.FAILED, 0, 0, List.of(busy.getMessage())))));
      }
    }
    return reports;
  }

  /**
   * Runs one job, or every job of the source when {@code jobName} is null.
   *
   * @throws UnknownJobException the source or job does not exist
   * @throws TriggerBusyException the source is already being triggered
   */
  public TriggerReport run(String source, String jobName) {
    List<CollectionJob> jobs = bySource.get(source);
    if (jobs == null) {
      throw new UnknownJobException("unknown source: " + source);
    }
    List<CollectionJob> selected =
        jobName == null ? jobs : jobs.stream().filter(j -> j.name().equals(jobName)).toList();
    if (selected.isEmpty()) {
      throw new UnknownJobException("unknown job: " + source + "/" + jobName);
    }
    ReentrantLock lock = locks.get(source);
    if (!lock.tryLock()) {
      throw new TriggerBusyException(source);
    }
    try {
      return new TriggerReport(source, selected.stream().map(this::runOne).toList());
    } finally {
      lock.unlock();
    }
  }

  private JobReport runOne(CollectionJob job) {
    long start = System.nanoTime();
    try {
      CollectionResult result = job.run();
      return new JobReport(
          job.name(), result.status(), result.runs(), millisSince(start), result.errors());
    } catch (RuntimeException e) {
      log.error("[trigger] {}/{} failed", job.source(), job.name(), e);
      return new JobReport(
          job.name(), SyncStatus.FAILED, 0, millisSince(start), List.of(describe(e)));
    }
  }

  private static long millisSince(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private static String describe(Exception e) {
    String text = e.getClass().getSimpleName() + ": " + e.getMessage();
    return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
  }
}
