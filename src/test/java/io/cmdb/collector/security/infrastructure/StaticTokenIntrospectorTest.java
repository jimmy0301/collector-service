package io.cmdb.collector.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;

class StaticTokenIntrospectorTest {

  @Test
  void acceptsTheConfiguredTokenAndGrantsItsAuthority() {
    var principal = new StaticTokenIntrospector("s3cret", "ROLE_TOKEN_ADMIN").introspect("s3cret");

    assertThat(principal.getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_TOKEN_ADMIN");
  }

  @Test
  void rejectsAnyOtherToken() {
    var introspector = new StaticTokenIntrospector("s3cret", "ROLE_TOKEN_ADMIN");

    assertThatThrownBy(() -> introspector.introspect("wrong"))
        .isInstanceOf(BadOpaqueTokenException.class);
    assertThatThrownBy(() -> introspector.introspect("s3cre"))
        .isInstanceOf(BadOpaqueTokenException.class);
    assertThatThrownBy(() -> introspector.introspect("s3cret-and-more"))
        .isInstanceOf(BadOpaqueTokenException.class);
  }

  @Test
  void rejectsEveryTokenWhenNoneIsConfigured() {
    assertThatThrownBy(() -> new StaticTokenIntrospector(null, "A").introspect("anything"))
        .isInstanceOf(BadOpaqueTokenException.class);
    assertThatThrownBy(() -> new StaticTokenIntrospector("  ", "A").introspect("  "))
        .isInstanceOf(BadOpaqueTokenException.class);
    assertThatThrownBy(() -> new StaticTokenIntrospector("", "A").introspect(""))
        .isInstanceOf(BadOpaqueTokenException.class);
  }
}
