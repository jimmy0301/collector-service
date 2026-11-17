package io.cmdb.collector.rawevent.domain;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RawEventTest {

  @Test
  void keepsIdentityWhenPayloadIsReplaced() {
    RawEvent original = new RawEvent("K8S", "Pod", "k8s:c:u:1", "k8s:c:u", json("{\"a\":1}"));

    RawEvent redacted = original.withPayload(json("{\"a\":\"***\"}"));

    assertThat(redacted.provider()).isEqualTo("K8S");
    assertThat(redacted.externalEventId()).isEqualTo("k8s:c:u:1");
    assertThat(redacted.orderingKey()).isEqualTo("k8s:c:u");
    assertThat(redacted.payload().path("a").asString()).isEqualTo("***");
    assertThat(original.payload().path("a").asInt()).isEqualTo(1);
  }

  @Test
  void rejectsMissingOrTooLongFields() {
    assertThatThrownBy(() -> new RawEvent(" ", "Pod", "id", null, json("{}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RawEvent("K8S", "Pod", "x".repeat(201), null, json("{}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RawEvent("K8S", "Pod", "id", "x".repeat(301), json("{}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RawEvent("K8S", "Pod", "id", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
