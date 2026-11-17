package io.cmdb.collector.security.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Accepts the one configured token and grants it one authority. Used only to protect POST
 * /auth/token (the bootstrap: something has to be allowed to ask for the first JWT). With no token
 * configured every token is rejected.
 */
class StaticTokenIntrospector implements OpaqueTokenIntrospector {

  private final byte[] expected;
  private final String authority;

  StaticTokenIntrospector(String token, String authority) {
    this.expected =
        token == null || token.isBlank() ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    this.authority = authority;
  }

  @Override
  public OAuth2AuthenticatedPrincipal introspect(String token) {
    // constant-time comparison, so response timing does not reveal how much of the token matched
    if (expected.length == 0
        || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected)) {
      throw new BadOpaqueTokenException("invalid token");
    }
    return new DefaultOAuth2AuthenticatedPrincipal(
        "collector-admin",
        Map.of("authority", authority),
        AuthorityUtils.createAuthorityList(authority));
  }
}
