package io.cmdb.collector.support;

import io.cmdb.collector.rawevent.domain.PendingKind;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventRepository;
import io.cmdb.collector.syncrun.domain.SyncRun;
import io.cmdb.collector.syncrun.domain.SyncRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** In-memory test doubles. */
public final class Fakes {

  public static final JsonMapper JSON = JsonMapper.builder().build();

  private Fakes() {}

  public static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  public static class InMemorySink implements RawEventRepository {
    public final List<RawEvent> events = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    private long sequence;

    @Override
    public Optional<Long> saveIfAbsent(RawEvent event) {
      if (!keys.add(event.provider() + '|' + event.externalEventId())) {
        return Optional.empty();
      }
      events.add(event);
      return Optional.of(++sequence);
    }

    @Override
    public Optional<RawEvent> findById(Long id) {
      return id >= 1 && id <= events.size()
          ? Optional.of(events.get((int) (id - 1)))
          : Optional.empty();
    }

    @Override
    public void markProcessed(Long id) {}

    @Override
    public int recordFailure(Long id, String error) {
      return 1;
    }

    @Override
    public void markDead(Long id, String error) {}

    @Override
    public Optional<RawEvent> findByProviderAndExternalEventId(String provider, String id) {
      return events.stream()
          .filter(e -> e.provider().equals(provider) && e.externalEventId().equals(id))
          .findFirst();
    }

    /** Every landed event counts as PENDING (this fake does not track processing). */
    @Override
    public List<PendingKind> pendingKinds(Instant receivedBefore) {
      return events.stream()
          .map(e -> new PendingKind(e.provider(), e.eventType()))
          .distinct()
          .toList();
    }

    @Override
    public List<RawEvent> findPending(
        String provider, String eventType, Instant receivedBefore, int limit) {
      return events.stream()
          .filter(e -> e.provider().equals(provider) && e.eventType().equals(eventType))
          .limit(limit)
          .toList();
    }

    public List<RawEvent> ofType(String type) {
      return events.stream().filter(e -> e.eventType().equals(type)).toList();
    }
  }

  /** The same SyncRun object is saved twice (start, finish); deduplicate by object identity. */
  public static class InMemoryRuns implements SyncRunRepository {
    public final List<SyncRun> runs = new ArrayList<>();

    @Override
    public void save(SyncRun run) {
      if (runs.stream().noneMatch(r -> r == run)) {
        runs.add(run);
      }
    }

    @Override
    public Optional<SyncRun> findById(Long id) {
      return Optional.empty();
    }

    @Override
    public int deleteStartedBefore(Instant cutoff, int batchSize) {
      return 0;
    }

    public SyncRun last() {
      return runs.get(runs.size() - 1);
    }
  }
}
