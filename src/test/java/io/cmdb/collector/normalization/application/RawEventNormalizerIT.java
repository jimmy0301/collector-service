package io.cmdb.collector.normalization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cmdb.collector.support.PipelineTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

/** Failure handling (retry, DEAD) and event types that have no handler yet. */
class RawEventNormalizerIT extends PipelineTest {

  @Autowired IncompleteEventPublications incomplete;
  @Autowired PendingEventSweeper sweeper;
  @Autowired io.cmdb.collector.rawevent.domain.RawEventRepository rawEvents;

  @Test
  void failedEventIsRetriedAndBecomesDeadAfterMaxAttempts() {
    String ordering = "k8s:%s:%s".formatted(clusterId, podUid);
    record(
        "K8S",
        "Pod",
        "bad",
        ordering,
        payloads.k8s("not-a-time", payloads.pod("1", "Running", null)));

    awaitStatus("K8S", "bad", "FAILED");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.sql(
                                "SELECT status FROM event_publication WHERE serialized_event LIKE ?")
                            .param("%" + run + "%")
                            .query(String.class)
                            .optional()
                            .orElse("(none)"))
                    .isEqualTo("FAILED"));
    assertThat(
            jdbc.sql("SELECT attempt_count FROM integration_raw_events WHERE external_event_id=?")
                .param("bad-" + run)
                .query(Integer.class)
                .single())
        .isEqualTo(1);

    incomplete.resubmitIncompletePublications(
        ResubmissionOptions.defaults().withMinAge(Duration.ZERO));

    awaitStatus("K8S", "bad", "DEAD"); // max-attempts = 2
    var row =
        jdbc.sql(
                "SELECT attempt_count, last_error FROM integration_raw_events WHERE external_event_id=?")
            .param("bad-" + run)
            .query()
            .singleRow();
    assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(2);
    assertThat(row.get("last_error").toString()).contains("DateTimeParseException");
    // After DEAD the publication is completed and deleted (completion-mode=delete) and is never
    // resubmitted
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        count(
                            "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                            "%" + run + "%"))
                    .isZero());
  }

  @Test
  void eventsWithoutAHandlerYetStayPendingAndLeaveNoPublicationBehind() {
    record(
        "ARGO",
        "SOME_FUTURE_KIND",
        "app1",
        "argo:" + site + ":u1",
        "{\"site\":\"%s\",\"resource\":{}}".formatted(site));

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        count(
                            "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                            "%" + run + "%"))
                    .isZero());
    // Without a handler the event is not processed, and a handler added later can still pick it up
    assertThat(status("ARGO", "app1")).isEqualTo("PENDING");
  }

  @Test
  void aPendingEventThatHasAHandlerNowIsSubmittedBySweepingAndProcessed() {
    // landed straight into the table, the way an event is when no handler existed: PENDING and no
    // publication
    rawEvents.saveIfAbsent(
        new io.cmdb.collector.rawevent.domain.RawEvent(
            "K8S",
            "SITE_SNAPSHOT",
            "late-" + run,
            "k8s:sites",
            io.cmdb.collector.support.Fakes.json("{\"items\":[{\"code\":\"" + site + "\"}]}")));
    assertThat(status("K8S", "late")).isEqualTo("PENDING");

    sweeper.sweep(Duration.ZERO, 100);

    awaitProcessed("K8S", "late");
    assertThat(count("SELECT count(*) FROM sites WHERE code=?", site)).isEqualTo(1);
  }
}
