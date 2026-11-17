package io.cmdb.collector.rawevent.domain;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentFingerprintTest {

  @Test
  void isIndependentOfKeyOrder() {
    assertThat(ContentFingerprint.of(json("{\"a\":1,\"b\":{\"x\":[1,2],\"y\":null}}")))
        .isEqualTo(ContentFingerprint.of(json("{\"b\":{\"y\":null,\"x\":[1,2]},\"a\":1}")));
  }

  @Test
  void changesWhenContentOrArrayOrderChanges() {
    String base = ContentFingerprint.of(json("{\"a\":[1,2]}"));
    assertThat(ContentFingerprint.of(json("{\"a\":[2,1]}"))).isNotEqualTo(base);
    assertThat(ContentFingerprint.of(json("{\"a\":[1,3]}"))).isNotEqualTo(base);
    assertThat(ContentFingerprint.of(json("{\"a\":\"1\"}")))
        .isNotEqualTo(ContentFingerprint.of(json("{\"a\":1}")));
  }

  @Test
  void is16HexChars() {
    assertThat(ContentFingerprint.of(json("{}"))).matches("[0-9a-f]{16}");
  }
}
