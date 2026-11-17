package io.cmdb.collector.security.infrastructure;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.cmdb.collector.security.domain.IssuedToken;
import io.cmdb.collector.security.domain.TokenIssuer;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Signs tokens with RS256. When an encryption key is given the signed token is then encrypted
 * (nested JWT: a JWE whose content type is JWT, RSA-OAEP-256 with A256GCM), so the claims cannot be
 * read by whoever holds the token.
 */
class NimbusTokenIssuer implements TokenIssuer {

  static final String ROLES_CLAIM = "roles";

  private final String issuer;
  private final String keyId;
  private final RSAPrivateKey signingKey;
  private final RSAPublicKey encryptionKey; // null: sign only
  private final Clock clock;

  NimbusTokenIssuer(
      String issuer,
      String keyId,
      RSAPrivateKey signingKey,
      RSAPublicKey encryptionKey,
      Clock clock) {
    this.issuer = issuer;
    this.keyId = keyId;
    this.signingKey = signingKey;
    this.encryptionKey = encryptionKey;
    this.clock = clock;
  }

  @Override
  public IssuedToken issue(String subject, List<String> roles, Duration lifetime) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(lifetime);
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .jwtID(UUID.randomUUID().toString())
            .claim(ROLES_CLAIM, roles)
            .build();
    try {
      var signed =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(keyId)
                  .type(JOSEObjectType.JWT)
                  .build(),
              claims);
      signed.sign(new RSASSASigner(signingKey));
      String token = encryptionKey == null ? signed.serialize() : encrypt(signed);
      return new IssuedToken(token, subject, roles, expiresAt);
    } catch (JOSEException e) {
      throw new IllegalStateException("cannot issue a token", e);
    }
  }

  private String encrypt(SignedJWT signed) throws JOSEException {
    var jwe =
        new JWEObject(
            new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                .contentType("JWT") // marks a nested JWT: the content is a signed JWT
                .keyID(keyId)
                .build(),
            new Payload(signed));
    jwe.encrypt(new RSAEncrypter(encryptionKey));
    return jwe.serialize();
  }
}
