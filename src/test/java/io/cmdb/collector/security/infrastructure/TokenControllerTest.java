package io.cmdb.collector.security.infrastructure;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cmdb.collector.security.application.TokenService;
import io.cmdb.collector.security.domain.IssuedToken;
import io.cmdb.collector.security.domain.TokenPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract of the token API (body, status codes) through the real Spring MVC setup: only this
 * controller and the web layer start, with Boot's JSON and error-body configuration. The security
 * filters are switched off here; who may call it is TokenSecurityTest, the real signing is
 * JwtRoundTripTest.
 */
@WebMvcTest(controllers = TokenController.class, properties = "collector.security.jwt.enabled=true")
@AutoConfigureMockMvc(addFilters = false)
@Import(TokenControllerTest.Beans.class)
class TokenControllerTest {

  @TestConfiguration
  static class Beans {
    @Bean
    TokenService tokenService() {
      return new TokenService(
          (subject, roles, lifetime) ->
              new IssuedToken(
                  "signed-" + subject,
                  subject,
                  roles,
                  Instant.parse("2026-11-15T10:00:00Z").plus(lifetime)),
          new TokenPolicy(Set.of("COLLECT"), Duration.ofHours(1), Duration.ofHours(24)));
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void issuesATokenForTheRequestedSubjectAndRoles() throws Exception {
    mvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"ops\",\"roles\":[\"COLLECT\"],\"expiresInSeconds\":600}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("signed-ops"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.subject").value("ops"))
        .andExpect(jsonPath("$.roles[0]").value("COLLECT"))
        .andExpect(jsonPath("$.expiresAt").value("2026-11-15T10:10:00Z"));
  }

  @Test
  void theDefaultLifetimeApplies() throws Exception {
    mvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"ops\",\"roles\":[\"COLLECT\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresAt").value("2026-11-15T11:00:00Z"));
  }

  @Test
  void aRequestThePolicyRefusesIs400AndSaysWhy() throws Exception {
    mvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"ops\",\"roles\":[\"ROOT\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("role not allowed: ROOT")));
    mvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\" \",\"roles\":[\"COLLECT\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("subject is required")));
    mvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"subject\":\"ops\",\"roles\":[\"COLLECT\"],\"expiresInSeconds\":999999}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("maximum")));
  }

  @Test
  void aBodyThatIsNotJsonIs400() throws Exception {
    mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content("not json"))
        .andExpect(status().isBadRequest());
  }
}
