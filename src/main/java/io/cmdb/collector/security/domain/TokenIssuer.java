package io.cmdb.collector.security.domain;

import java.time.Duration;
import java.util.List;

/** Port: creates a signed (and, when configured, encrypted) token. No validation of the request. */
public interface TokenIssuer {

  IssuedToken issue(String subject, List<String> roles, Duration lifetime);
}
