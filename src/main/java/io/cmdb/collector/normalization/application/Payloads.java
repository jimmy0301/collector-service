package io.cmdb.collector.normalization.application;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Small helpers for reading a raw event payload; a missing required field throws (the event is
 * marked failed and the reason is kept).
 */
final class Payloads {

  private Payloads() {}

  static String required(JsonNode node, String field) {
    String value = node.path(field).asString("");
    if (value.isBlank()) {
      throw new IllegalArgumentException("payload is missing required field: " + field);
    }
    return value;
  }

  static Instant instant(JsonNode node, String field) {
    return Instant.parse(required(node, field));
  }

  static Instant optionalInstant(JsonNode node) {
    String value = node.asString("");
    return value.isBlank() ? null : Instant.parse(value);
  }
}
