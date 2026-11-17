package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.rawevent.application.RawEventLifecycle;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventStored;
import io.cmdb.collector.support.Fakes.InMemorySink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PendingEventSweeperTest {

  static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");

  /** Only handles ARGO / APPLICATION_SNAPSHOT. */
  static class Handler implements RawEventHandler {
    @Override
    public boolean supports(String provider, String eventType) {
      return "ARGO".equals(provider) && "APPLICATION_SNAPSHOT".equals(eventType);
    }

    @Override
    public void handle(RawEvent event) {}
  }

  final InMemorySink repo = new InMemorySink();
  final List<Object> published = new ArrayList<>();
  final PendingEventSweeper sweeper =
      new PendingEventSweeper(
          repo,
          new RawEventNormalizer(List.of(new Handler()), repo, new RawEventLifecycle(repo), 5),
          published::add,
          Clock.fixed(NOW, ZoneOffset.UTC));

  void land(String provider, String type, String id) {
    repo.saveIfAbsent(new RawEvent(provider, type, id, "key-" + id, json("{}")));
  }

  @Test
  void submitsPendingEventsOfKindsThatHaveAHandler() {
    land("ARGO", "APPLICATION_SNAPSHOT", "a1");
    land("ARGO", "APPLICATION_SNAPSHOT", "a2");

    assertThat(sweeper.sweep(Duration.ofMinutes(10), 100)).isEqualTo(2);

    assertThat(published)
        .extracting(e -> ((RawEventStored) e).orderingKey())
        .containsExactly("key-a1", "key-a2");
    assertThat(published).allSatisfy(e -> assertThat(e).isInstanceOf(RawEventStored.class));
    var first = (RawEventStored) published.get(0);
    assertThat(first.provider()).isEqualTo("ARGO");
    assertThat(first.eventType()).isEqualTo("APPLICATION_SNAPSHOT");
  }

  @Test
  void kindsWithoutAHandlerAreLeftPending() {
    land("ARGO", "SOME_FUTURE_KIND", "x1");
    land("ADO", "RELEASE", "x2");

    assertThat(sweeper.sweep(Duration.ofMinutes(10), 100)).isZero();

    assertThat(published).isEmpty();
  }

  @Test
  void theBatchSizeLimitsHowManyAreSubmittedPerKindPerRun() {
    for (int i = 0; i < 5; i++) {
      land("ARGO", "APPLICATION_SNAPSHOT", "a" + i);
    }

    assertThat(sweeper.sweep(Duration.ofMinutes(10), 3)).isEqualTo(3);
  }

  @Test
  void nothingPendingSubmitsNothing() {
    assertThat(sweeper.sweep(Duration.ofMinutes(10), 100)).isZero();
  }
}
