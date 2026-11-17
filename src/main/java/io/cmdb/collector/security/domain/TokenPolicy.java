package io.cmdb.collector.security.domain;

import java.time.Duration;
import java.util.Set;

/** What a token request may ask for: the roles that can be granted and the lifetime bounds. */
public record TokenPolicy(
    Set<String> allowedRoles, Duration defaultLifetime, Duration maxLifetime) {

  public TokenPolicy {
    allowedRoles = Set.copyOf(allowedRoles);
    if (defaultLifetime.compareTo(maxLifetime) > 0) {
      throw new IllegalArgumentException("the default lifetime exceeds the maximum");
    }
  }
}
