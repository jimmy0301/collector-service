package io.cmdb.collector.security.infrastructure;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cmdb.collector.security.application.TokenService;
import io.cmdb.collector.security.domain.IssuedToken;
import io.cmdb.collector.security.domain.TokenIssuer;
import io.cmdb.collector.security.domain.TokenPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Who may ask for tokens, with the real filter chain: only the web layer starts. */
@WebMvcTest(
    controllers = TokenController.class,
    properties = {
      "collector.security.admin-token=admin-s3cret",
      "collector.security.jwt.enabled=true"
    })
@Import({TokenSecurityConfig.class, TokenSecurityTest.Beans.class})
class TokenSecurityTest {

  static final String BODY = "{\"subject\":\"ops\",\"roles\":[\"COLLECT\"]}";

  @TestConfiguration
  static class Beans {
    @Bean
    TokenIssuer issuer() {
      return (subject, roles, lifetime) ->
          new IssuedToken(
              "signed", subject, roles, Instant.parse("2026-11-15T10:00:00Z").plus(lifetime));
    }

    @Bean
    TokenService tokenService(TokenIssuer issuer) {
      return new TokenService(
          issuer, new TokenPolicy(Set.of("COLLECT"), Duration.ofHours(1), Duration.ofHours(24)));
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void theAdminTokenMayAskForATokenAndAnythingElseMayNot() throws Exception {
    mvc.perform(
            post("/auth/token")
                .header("Authorization", "Bearer admin-s3cret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk());
    mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
    mvc.perform(
            post("/auth/token")
                .header("Authorization", "Bearer wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized());
  }
}
