package io.cmdb.collector.security.domain;

import java.time.Instant;
import java.util.List;

/** A token that has been issued, with what it says. */
public record IssuedToken(String token, String subject, List<String> roles, Instant expiresAt) {}
