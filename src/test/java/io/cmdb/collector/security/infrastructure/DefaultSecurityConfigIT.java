package io.cmdb.collector.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cmdb.collector.support.PostgresIntegrationTest;
import io.cmdb.collector.support.TestKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring check with the whole application and encrypted tokens: the filter chains together protect
 * what they should, leave the K8s probes and the Prometheus scrape open, and a token issued by the
 * collector (signed and encrypted with the configured keys) opens the collect API. This is also the
 * one place that would notice the application locking out its own health checks.
 */
@AutoConfigureMockMvc
class DefaultSecurityConfigIT extends PostgresIntegrationTest {

  static final String ADMIN = "wiring-admin-token";

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) throws IOException {
    Path dir = Files.createTempDirectory("collector-keys");
    var sign = TestKeys.rsa(2048);
    var encrypt = TestKeys.rsa(2048);
    registry.add("collector.security.admin-token", () -> ADMIN);
    registry.add("collector.security.jwt.enabled", () -> "true");
    registry.add(
        "collector.security.jwt.signing-private-key",
        () -> "file:" + TestKeys.writePrivate(dir, "sign.key", sign));
    registry.add(
        "collector.security.jwt.signing-public-key",
        () -> "file:" + TestKeys.writePublic(dir, "sign.pub", sign));
    registry.add(
        "collector.security.jwt.encryption-private-key",
        () -> "file:" + TestKeys.writePrivate(dir, "enc.key", encrypt));
    registry.add(
        "collector.security.jwt.encryption-public-key",
        () -> "file:" + TestKeys.writePublic(dir, "enc.pub", encrypt));
  }

  @Autowired MockMvc mvc;

  String issue(String role) throws Exception {
    var body =
        mvc.perform(
                post("/auth/token")
                    .header("Authorization", "Bearer " + ADMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"subject\":\"wiring\",\"roles\":[\""
                            + role
                            + "\"],\"expiresInSeconds\":300}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonMapper.shared().readTree(body).path("token").asString();
  }

  @Test
  void theK8sProbesAndPrometheusStayOpenWithoutAToken() throws Exception {
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
  }

  @Test
  void theErrorPageStaysReachableSoAHandlersStatusIsNotReplacedBy403() throws Exception {
    // the container dispatches here after a handler fails with 404 or 409 (MockMvc does not do that
    // dispatch itself, so the path is checked directly; a real jar returned 403 for 404 before)
    int status = mvc.perform(get("/error")).andReturn().getResponse().getStatus();

    assertThat(status).isNotIn(401, 403);
  }

  @Test
  void aTokenIssuedByTheCollectorOpensTheCollectApi() throws Exception {
    String token = issue("COLLECT");

    assertThat(token.split("\\.")).hasSize(5); // encrypted: a JWE
    mvc.perform(get("/collect").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void theCollectApiRefusesWhatIsNotAnIssuedTokenWithTheCollectRole() throws Exception {
    mvc.perform(get("/collect")).andExpect(status().isUnauthorized());
    mvc.perform(get("/collect").header("Authorization", "Bearer " + ADMIN))
        .andExpect(status().isUnauthorized()); // the admin token only asks for tokens
    mvc.perform(get("/collect").header("Authorization", "Bearer not.a.token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void theTokenApiNeedsTheAdminTokenAndOnlyGrantsAllowedRoles() throws Exception {
    String body = "{\"subject\":\"x\",\"roles\":[\"COLLECT\"]}";
    mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/auth/token")
                .header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"x\",\"roles\":[\"ROOT\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail")
                .value(org.hamcrest.Matchers.containsString("role not allowed: ROOT")));
  }

  @Test
  void aRefusedRequestSaysWhyInTheBody() throws Exception {
    String token = issue("COLLECT");

    mvc.perform(post("/collect/nope").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail")
                .value(org.hamcrest.Matchers.containsString("unknown source: nope")));
  }

  @Test
  void everythingElseIsDeniedByDefault() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    mvc.perform(post("/anything")).andExpect(status().isForbidden());
    mvc.perform(get("/")).andExpect(status().isForbidden());
  }
}
