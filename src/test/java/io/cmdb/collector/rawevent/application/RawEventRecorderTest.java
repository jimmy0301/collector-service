package io.cmdb.collector.rawevent.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventStored;
import io.cmdb.collector.support.Fakes.InMemorySink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawEventRecorderTest {

  final InMemorySink sink = new InMemorySink();
  final List<Object> published = new ArrayList<>();
  final RawEventRecorder recorder =
      new RawEventRecorder(sink, published::add, new SimpleMeterRegistry(), 100);

  static RawEvent event(String id, String payload) {
    return new RawEvent("TEST", "X", id, "k:" + id, json(payload));
  }

  @Test
  void redactsBeforeLanding() {
    recorder.record(event("e1", "{\"env\":[{\"name\":\"P\",\"value\":\"hunter2\"}]}"));

    assertThat(sink.events.get(0).payload().toString())
        .contains("\"name\"")
        .doesNotContain("hunter2");
  }

  @Test
  void newEventIsLandedAndPublishedOnce() {
    assertThat(recorder.record(event("e1", "{}"))).isTrue();

    assertThat(sink.events).hasSize(1);
    assertThat(published).singleElement().isInstanceOf(RawEventStored.class);
  }

  @Test
  void aRepeatedEventIsNeitherLandedNorPublishedAgain() {
    recorder.record(event("e1", "{}"));

    assertThat(recorder.record(event("e1", "{\"changed\":true}"))).isFalse();

    assertThat(sink.events).hasSize(1);
    assertThat(published).hasSize(1);
  }

  @Test
  void anEventAlreadyInTheDatabaseIsNotPublishedEvenWhenTheCacheIsCold() {
    var other = new RawEventRecorder(sink, published::add, new SimpleMeterRegistry(), 100);
    recorder.record(event("e1", "{}")); // e.g. landed by another instance or before a restart
    published.clear();

    assertThat(other.record(event("e1", "{}"))).isFalse();
    assertThat(published).isEmpty();
  }

  @Test
  void theCacheIsBoundedAndForgetsTheOldestEvents() {
    var small = new RawEventRecorder(sink, published::add, new SimpleMeterRegistry(), 2);
    small.record(event("a", "{}"));
    small.record(event("b", "{}"));
    small.record(event("c", "{}")); // evicts "a" from the cache

    // "a" passes the cache again but the repository still deduplicates it
    assertThat(small.record(event("a", "{}"))).isFalse();
    assertThat(sink.events).hasSize(3);
  }
}
