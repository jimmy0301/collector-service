package io.cmdb.collector.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.cmdb.collector.support.TestKeys;
import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.trigger.application.CollectionTrigger;
import io.cmdb.collector.trigger.domain.CollectionJob;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may call the trigger API, with the real filter chain: only the web layer starts (this
 * controller and the security configuration), no database and no collectors. The tokens are signed
 * here the way the collector signs its own (RS256, a roles claim).
 */
@WebMvcTest(controllers = TriggerController.class)
@Import({TriggerSecurityConfig.class, TriggerSecurityTest.Beans.class})
class TriggerSecurityTest {

  static final KeyPair SIGNER = TestKeys.rsa(2048);
  static final KeyPair STRANGER = TestKeys.rsa(2048);
  static final List<String> ran = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class Beans {
    @Bean
    CollectionTrigger trigger() {
      return new CollectionTrigger(
          List.of(
              CollectionJob.of(
                  "argo",
                  "sites",
                  () -> {
                    ran.add("argo/sites");
                    return CollectionResult.none().with(SyncStatus.SUCCESS);
                  })));
    }

    @Bean
    JwtDecoder jwtDecoder() {
      var decoder =
          NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNER.getPublic())
              .signatureAlgorithm(SignatureAlgorithm.RS256)
              .build();
      return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
      var authorities = new JwtGrantedAuthoritiesConverter();
      authorities.setAuthoritiesClaimName("roles");
      authorities.setAuthorityPrefix("ROLE_");
      var converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter(authorities);
      return converter;
    }
  }

  @Autowired MockMvc mvc;

  @BeforeEach
  void clear() {
    ran.clear();
  }

  static String token(KeyPair signer, List<String> roles, Instant expires) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject("ops")
            .expirationTime(Date.from(expires))
            .claim("roles", roles)
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(signer.getPrivate()));
    return jwt.serialize();
  }

  static String bearer(String token) {
    return "Bearer " + token;
  }

  @Test
  void aValidTokenWithTheCollectRoleIsAllowed() throws Exception {
    String token = token(SIGNER, List.of("COLLECT"), Instant.now().plusSeconds(600));

    mvc.perform(post("/collect/argo/sites").header("Authorization", bearer(token)))
        .andExpect(status().isOk());
    mvc.perform(get("/collect").header("Authorization", bearer(token))).andExpect(status().isOk());
    assertThat(ran).containsExactly("argo/sites");
  }

  @Test
  void noTokenIs401WithABearerChallengeAndNothingRuns() throws Exception {
    mvc.perform(post("/collect/argo/sites"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
    mvc.perform(get("/collect")).andExpect(status().isUnauthorized());
    assertThat(ran).isEmpty();
  }

  @Test
  void aTokenSignedByAStrangerIs401() throws Exception {
    String forged = token(STRANGER, List.of("COLLECT"), Instant.now().plusSeconds(600));

    mvc.perform(post("/collect/argo/sites").header("Authorization", bearer(forged)))
        .andExpect(status().isUnauthorized());
    assertThat(ran).isEmpty();
  }

  @Test
  void anExpiredTokenIs401() throws Exception {
    String expired = token(SIGNER, List.of("COLLECT"), Instant.now().minusSeconds(600));

    mvc.perform(post("/collect/argo/sites").header("Authorization", bearer(expired)))
        .andExpect(status().isUnauthorized());
    assertThat(ran).isEmpty();
  }

  @Test
  void aValidTokenWithoutTheCollectRoleIs403() throws Exception {
    String token = token(SIGNER, List.of("VIEW"), Instant.now().plusSeconds(600));

    mvc.perform(post("/collect/argo/sites").header("Authorization", bearer(token)))
        .andExpect(status().isForbidden());
    assertThat(ran).isEmpty();
  }

  @Test
  void aTokenInAnotherSchemeIs401() throws Exception {
    mvc.perform(post("/collect/argo/sites").header("Authorization", "Basic czNjcmV0"))
        .andExpect(status().isUnauthorized());
    assertThat(ran).isEmpty();
  }
}
