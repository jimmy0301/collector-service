package io.cmdb.collector.security.application;

import io.cmdb.collector.security.domain.InvalidTokenRequestException;
import io.cmdb.collector.security.domain.IssuedToken;
import io.cmdb.collector.security.domain.TokenIssuer;
import io.cmdb.collector.security.domain.TokenPolicy;
import java.time.Duration;
import java.util.List;

/**
 * Checks a token request against the policy and issues the token. The caller has already been
 * authorized to ask for tokens; what it may put in them is decided here. Created by {@code
 * JwtConfig} only when token keys are configured.
 */
public class TokenService {

  private final TokenIssuer issuer;
  private final TokenPolicy policy;

  public TokenService(TokenIssuer issuer, TokenPolicy policy) {
    this.issuer = issuer;
    this.policy = policy;
  }

  /**
   * @param lifetimeSeconds how long the token lives; the policy default when null
   * @throws InvalidTokenRequestException a blank subject, no roles, a role the policy does not
   *     allow, or a lifetime that is not positive or exceeds the maximum
   */
  public IssuedToken issue(String subject, List<String> roles, Long lifetimeSeconds) {
    if (subject == null || subject.isBlank()) {
      throw new InvalidTokenRequestException("subject is required");
    }
    if (roles == null || roles.isEmpty()) {
      throw new InvalidTokenRequestException("at least one role is required");
    }
    for (String role : roles) {
      if (!policy.allowedRoles().contains(role)) {
        throw new InvalidTokenRequestException(
            "role not allowed: " + role + " (allowed: " + policy.allowedRoles() + ")");
      }
    }
    Duration lifetime =
        lifetimeSeconds == null ? policy.defaultLifetime() : Duration.ofSeconds(lifetimeSeconds);
    if (lifetime.isNegative() || lifetime.isZero()) {
      throw new InvalidTokenRequestException("the lifetime must be positive");
    }
    if (lifetime.compareTo(policy.maxLifetime()) > 0) {
      throw new InvalidTokenRequestException(
          "the lifetime exceeds the maximum of " + policy.maxLifetime().toSeconds() + " seconds");
    }
    return issuer.issue(subject.trim(), List.copyOf(roles), lifetime);
  }
}
