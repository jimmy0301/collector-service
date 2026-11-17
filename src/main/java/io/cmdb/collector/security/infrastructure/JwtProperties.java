package io.cmdb.collector.security.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * The collector's own token keys. Keys are PEM files (usually a mounted K8s Secret): {@code
 * file:/keys/sign.key}. {@code enabled} is false by default, which switches token issuing and JWT
 * verification off; when it is true the signing keys are required.
 *
 * <p>{@code signing*}: RS256 key pair (PKCS#8 private key, X.509 public key, at least 2048 bits).
 * {@code encryption*}: optional RSA key pair; when set, tokens are also encrypted (a signed JWT
 * inside a JWE, RSA-OAEP-256 with A256GCM) and only encrypted tokens are accepted.
 */
@ConfigurationProperties("collector.security.jwt")
record JwtProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("collector-service") String issuer,
    @DefaultValue("collector-1") String keyId,
    Resource signingPrivateKey,
    Resource signingPublicKey,
    Resource encryptionPrivateKey,
    Resource encryptionPublicKey,
    @DefaultValue("1h") Duration defaultLifetime,
    @DefaultValue("24h") Duration maxLifetime,
    @DefaultValue("COLLECT") List<String> allowedRoles) {

  boolean encrypted() {
    return encryptionPublicKey != null || encryptionPrivateKey != null;
  }
}
