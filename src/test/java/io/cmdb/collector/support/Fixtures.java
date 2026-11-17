package io.cmdb.collector.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads JSON fixtures from {@code src/test/resources/fixtures}, written after
 * INTEGRATION_API_SPEC.md.
 */
public final class Fixtures {

  private Fixtures() {}

  public static String load(String path) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
      if (in == null) {
        throw new IllegalArgumentException("fixture not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
