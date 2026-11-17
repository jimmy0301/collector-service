package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.rawevent.application.RawEventLifecycle;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventStored;
import io.cmdb.collector.support.Fakes.InMemorySink;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawEventNormalizerTest {

  /** Records the lifecycle calls the normalizer causes. */
  static class Repo extends InMemorySink {
    final List<Long> processed = new ArrayList<>();
    final List<Long> dead = new ArrayList<>();
    int failures;

    @Override
    public void markProcessed(Long id) {
      processed.add(id);
    }

    @Override
    public int recordFailure(Long id, String error) {
      return ++failures;
    }

    @Override
    public void markDead(Long id, String error) {
      dead.add(id);
    }
  }

  static class Handler implements RawEventHandler {
    final List<RawEvent> handled = new ArrayList<>();
    RuntimeException failure;

    @Override
    public boolean supports(String provider, String eventType) {
      return "K8S".equals(provider) && "Pod".equals(eventType);
    }

    @Override
    public void handle(RawEvent event) {
      if (failure != null) {
        throw failure;
      }
      handled.add(event);
    }
  }

  final Repo repo = new Repo();
  final Handler handler = new Handler();

  RawEventNormalizer normalizer(int maxAttempts) {
    return new RawEventNormalizer(List.of(handler), repo, new RawEventLifecycle(repo), maxAttempts);
  }

  Long land(String provider, String type) {
    return repo.saveIfAbsent(new RawEvent(provider, type, "id", "k", json("{}"))).orElseThrow();
  }

  @Test
  void aSupportedEventIsHandledAndMarkedProcessed() {
    Long id = land("K8S", "Pod");

    normalizer(5).on(new RawEventStored(id, "K8S", "Pod", "k"));

    assertThat(handler.handled).hasSize(1);
    assertThat(repo.processed).containsExactly(id);
  }

  @Test
  void anEventWithoutAHandlerIsLeftAlone() {
    Long id = land("ARGO", "APPLICATION_SNAPSHOT");

    normalizer(5).on(new RawEventStored(id, "ARGO", "APPLICATION_SNAPSHOT", "k"));

    assertThat(handler.handled).isEmpty();
    assertThat(repo.processed).isEmpty();
    assertThat(repo.failures).isZero();
  }

  @Test
  void aMissingRawEventIsSkippedWithoutFailing() {
    normalizer(5).on(new RawEventStored(99L, "K8S", "Pod", "k"));

    assertThat(handler.handled).isEmpty();
    assertThat(repo.processed).isEmpty();
  }

  @Test
  void aFailureIsRecordedAndRethrownSoThePublicationStaysIncomplete() {
    Long id = land("K8S", "Pod");
    handler.failure = new IllegalStateException("boom");

    assertThatThrownBy(() -> normalizer(3).on(new RawEventStored(id, "K8S", "Pod", "k")))
        .isSameAs(handler.failure);

    assertThat(repo.failures).isEqualTo(1);
    assertThat(repo.dead).isEmpty();
    assertThat(repo.processed).isEmpty();
  }

  @Test
  void afterMaxAttemptsTheEventIsDeadAndTheExceptionIsNoLongerThrown() {
    Long id = land("K8S", "Pod");
    handler.failure = new IllegalStateException("boom");
    var normalizer = normalizer(2);
    var stored = new RawEventStored(id, "K8S", "Pod", "k");

    assertThatThrownBy(() -> normalizer.on(stored)).isInstanceOf(IllegalStateException.class);
    normalizer.on(stored); // second attempt reaches max-attempts: DEAD, nothing is thrown

    assertThat(repo.failures).isEqualTo(2);
    assertThat(repo.dead).containsExactly(id);
  }
}
