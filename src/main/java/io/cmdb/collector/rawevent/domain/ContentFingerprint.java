package io.cmdb.collector.rawevent.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Content fingerprint: a JSON hash independent of field order. Polled objects have no event ID, so
 * it is used to build external_event_id; unchanged content has the same fingerprint and is
 * deduplicated, changed content produces a new event.
 */
public final class ContentFingerprint {

  private ContentFingerprint() {}

  /**
   * @return the first 16 hex characters of SHA-256 (64 bit)
   */
  public static String of(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    canonical(node, sb);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void canonical(JsonNode node, StringBuilder out) {
    if (node.isObject()) {
      out.append('{');
      for (String name : new TreeSet<>(node.propertyNames())) {
        out.append(name.length()).append(':').append(name).append('=');
        canonical(node.get(name), out);
        out.append(',');
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (JsonNode element : node) {
        canonical(element, out);
        out.append(',');
      }
      out.append(']');
    } else {
      out.append(node.toString());
    }
  }
}
