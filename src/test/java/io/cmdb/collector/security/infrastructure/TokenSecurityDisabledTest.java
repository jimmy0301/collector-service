package io.cmdb.collector.security.infrastructure;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cmdb.collector.security.application.TokenService;
import io.cmdb.collector.security.domain.TokenIssuer;
import io.cmdb.collector.security.domain.TokenPolicy;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Without an admin token the token API does not exist (404), even when keys are configured. */
@WebMvcTest(controllers = TokenController.class, properties = "collector.security.jwt.enabled=true")
@Import({TokenSecurityConfig.class, TokenSecurityDisabledTest.Beans.class})
class TokenSecurityDisabledTest {

  @TestConfiguration
  static class Beans {
    @Bean
    TokenIssuer issuer() {
      return (subject, roles, lifetime) -> {
        throw new AssertionError("must not be reached");
      };
    }

    @Bean
    TokenService tokenService(TokenIssuer issuer) {
      return new TokenService(
          issuer, new TokenPolicy(Set.of("COLLECT"), Duration.ofHours(1), Duration.ofHours(24)));
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void everyRequestIs404() throws Exception {
    String body = "{\"subject\":\"ops\",\"roles\":[\"COLLECT\"]}";
    mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/auth/token")
                .header("Authorization", "Bearer anything")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
  }
}
