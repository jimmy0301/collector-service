package io.cmdb.collector.rawevent.infrastructure;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.rawevent.domain.PendingKind;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.rawevent.domain.RawEventRepository;
import io.cmdb.collector.support.DatabaseTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(JpaRawEventRepository.class)
class JpaRawEventRepositoryIT extends DatabaseTest {

  @Autowired RawEventRepository rawEvents;

  static RawEvent event(String id, String payload) {
    return new RawEvent("K8S", "Pod", id, "k8s:c:" + id, json(payload));
  }

  @Test
  void saveIfAbsentIsIdempotentOnProviderAndExternalEventId() {
    assertThat(rawEvents.saveIfAbsent(event("it-1", "{\"a\":1}"))).isPresent();
    assertThat(rawEvents.saveIfAbsent(event("it-1", "{\"a\":2}")))
        .isEmpty(); // same ID → deduplicated, not overwritten

    var row =
        jdbc.sql(
                "SELECT payload, process_status, attempt_count FROM integration_raw_events WHERE external_event_id='it-1'")
            .query()
            .singleRow();
    assertThat(json(row.get("payload").toString()).path("a").asInt()).isEqualTo(1);
    assertThat(row.get("process_status")).isEqualTo("PENDING"); // database default
    assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
  }

  @Test
  void jsonbPayloadRoundTripsThroughTheAggregate() {
    rawEvents.saveIfAbsent(event("it-rt", "{\"a\":{\"b\":[1,2,{\"c\":\"x\"}]}}"));

    RawEvent loaded = rawEvents.findByProviderAndExternalEventId("K8S", "it-rt").orElseThrow();

    assertThat(loaded.id()).isNotNull();
    assertThat(loaded.eventType()).isEqualTo("Pod");
    assertThat(loaded.orderingKey()).isEqualTo("k8s:c:it-rt");
    assertThat(loaded.payload().at("/a/b/2/c").asString()).isEqualTo("x");
    assertThat(rawEvents.findByProviderAndExternalEventId("K8S", "missing")).isEmpty();
  }

  @Test
  void sameIdUnderDifferentProviderIsAllowed() {
    assertThat(rawEvents.saveIfAbsent(new RawEvent("ARGO", "X", "shared-id", null, json("{}"))))
        .isPresent();
    assertThat(rawEvents.saveIfAbsent(new RawEvent("K8S", "X", "shared-id", null, json("{}"))))
        .isPresent();
  }

  @Test
  void timestampsAreUtc() {
    assertThat(jdbc.sql("SHOW TIME ZONE").query(String.class).single()).isEqualTo("UTC");
    rawEvents.saveIfAbsent(event("it-utc", "{}"));
    Long driftSeconds =
        jdbc.sql(
                "SELECT ABS(EXTRACT(EPOCH FROM (now() - received_at)))::bigint FROM integration_raw_events WHERE external_event_id='it-utc'")
            .query(Long.class)
            .single();
    assertThat(driftSeconds).isLessThan(5);
  }

  // ---------- PENDING events ----------

  @Test
  void pendingKindsAndEventsListOnlyOldPendingOnesOfThatKindByIdOrder() {
    rawEvents.saveIfAbsent(new RawEvent("ARGO", "KIND_A_" + run, "p1", "k1", json("{}")));
    rawEvents.saveIfAbsent(new RawEvent("ARGO", "KIND_A_" + run, "p2", "k2", json("{}")));
    rawEvents.saveIfAbsent(new RawEvent("ARGO", "KIND_B_" + run, "p3", "k3", json("{}")));
    long processed =
        rawEvents
            .saveIfAbsent(new RawEvent("ARGO", "KIND_A_" + run, "p4", "k4", json("{}")))
            .orElseThrow();
    rawEvents.markProcessed(processed);
    Instant later = Instant.now().plusSeconds(60);

    assertThat(rawEvents.pendingKinds(later))
        .contains(
            new PendingKind("ARGO", "KIND_A_" + run), new PendingKind("ARGO", "KIND_B_" + run));
    assertThat(rawEvents.findPending("ARGO", "KIND_A_" + run, later, 10))
        .extracting(RawEvent::externalEventId)
        .containsExactly("p1", "p2"); // the processed one is not listed
    assertThat(rawEvents.findPending("ARGO", "KIND_A_" + run, later, 10))
        .allSatisfy(e -> assertThat(e.id()).isNotNull()); // the id is what gets submitted
    assertThat(rawEvents.findPending("ARGO", "KIND_A_" + run, later, 1)).hasSize(1);
  }

  @Test
  void aRecentlyReceivedEventIsNotPendingYetForTheSweeper() {
    rawEvents.saveIfAbsent(new RawEvent("ARGO", "KIND_C_" + run, "p1", "k1", json("{}")));
    Instant earlier = Instant.now().minusSeconds(600);

    assertThat(rawEvents.pendingKinds(earlier))
        .doesNotContain(new PendingKind("ARGO", "KIND_C_" + run));
    assertThat(rawEvents.findPending("ARGO", "KIND_C_" + run, earlier, 10)).isEmpty();
  }
}
