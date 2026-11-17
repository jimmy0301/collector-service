package io.cmdb.collector.security.infrastructure;

import io.cmdb.collector.security.application.TokenService;
import io.cmdb.collector.security.domain.TokenIssuer;
import io.cmdb.collector.security.domain.TokenPolicy;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * The collector's token keys turned into beans: a {@link TokenIssuer} that signs (and optionally
 * encrypts) and a {@link JwtDecoder} that verifies. Present only when {@code
 * collector.security.jwt.enabled} is true; the APIs that need them stay switched off otherwise. The
 * roles of a token become {@code ROLE_*} authorities.
 */
@Configuration
@ConditionalOnProperty(prefix = "collector.security.jwt", name = "enabled", havingValue = "true")
class JwtConfig {

  private final JwtProperties properties;
  private final RSAPrivateKey signingPrivateKey;
  private final RSAPublicKey signingPublicKey;
  private final RSAPrivateKey encryptionPrivateKey; // null: tokens are not encrypted
  private final RSAPublicKey encryptionPublicKey;

  JwtConfig(JwtProperties properties) {
    this.properties = properties;
    if (properties.signingPrivateKey() == null || properties.signingPublicKey() == null) {
      throw new IllegalStateException(
          "collector.security.jwt.enabled is true, so signing-private-key and signing-public-key are required");
    }
    if ((properties.encryptionPublicKey() == null) != (properties.encryptionPrivateKey() == null)) {
      throw new IllegalStateException(
          "set both collector.security.jwt.encryption-public-key and encryption-private-key, or neither");
    }
    // everything is read and checked here, so a bad key stops the application at startup
    signingPrivateKey = PemKeys.privateKey(properties.signingPrivateKey());
    signingPublicKey = PemKeys.publicKey(properties.signingPublicKey());
    PemKeys.requireSigningPair(
        signingPrivateKey, signingPublicKey, "collector.security.jwt.signing-*");
    if (properties.encrypted()) {
      encryptionPrivateKey = PemKeys.privateKey(properties.encryptionPrivateKey());
      encryptionPublicKey = PemKeys.publicKey(properties.encryptionPublicKey());
      PemKeys.requireEncryptionPair(
          encryptionPrivateKey, encryptionPublicKey, "collector.security.jwt.encryption-*");
    } else {
      encryptionPrivateKey = null;
      encryptionPublicKey = null;
    }
  }

  @Bean
  TokenIssuer tokenIssuer(Clock clock) {
    return new NimbusTokenIssuer(
        properties.issuer(), properties.keyId(), signingPrivateKey, encryptionPublicKey, clock);
  }

  @Bean
  TokenService tokenService(TokenIssuer issuer, TokenPolicy policy) {
    return new TokenService(issuer, policy);
  }

  @Bean
  JwtDecoder collectorJwtDecoder() {
    return new CollectorJwtDecoder(properties.issuer(), signingPublicKey, encryptionPrivateKey);
  }

  @Bean
  TokenPolicy tokenPolicy() {
    return new TokenPolicy(
        Set.copyOf(properties.allowedRoles()),
        properties.defaultLifetime(),
        properties.maxLifetime());
  }

  @Bean
  JwtAuthenticationConverter collectorJwtAuthenticationConverter() {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(NimbusTokenIssuer.ROLES_CLAIM);
    authorities.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }
}
