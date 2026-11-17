package io.cmdb.collector.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.cmdb.collector.support.TestKeys;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** The collector issues a token and verifies its own: signed only, and signed then encrypted. */
class JwtRoundTripTest {

  static final KeyPair SIGN = TestKeys.rsa(2048);
  static final KeyPair OTHER_SIGN = TestKeys.rsa(2048);
  static final KeyPair ENCRYPT = TestKeys.rsa(2048);
  static final KeyPair OTHER_ENCRYPT = TestKeys.rsa(2048);

  Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

  NimbusTokenIssuer signingOnly() {
    return new NimbusTokenIssuer(
        "collector-service", "k1", (RSAPrivateKey) SIGN.getPrivate(), null, clock);
  }

  NimbusTokenIssuer signingAndEncrypting() {
    return new NimbusTokenIssuer(
        "collector-service",
        "k1",
        (RSAPrivateKey) SIGN.getPrivate(),
        (RSAPublicKey) ENCRYPT.getPublic(),
        clock);
  }

  CollectorJwtDecoder verifyingOnly() {
    return new CollectorJwtDecoder("collector-service", (RSAPublicKey) SIGN.getPublic(), null);
  }

  CollectorJwtDecoder verifyingAndDecrypting() {
    return new CollectorJwtDecoder(
        "collector-service", (RSAPublicKey) SIGN.getPublic(), (RSAPrivateKey) ENCRYPT.getPrivate());
  }

  @Test
  void aSignedTokenCarriesTheSubjectRolesAndExpiryAndVerifies() {
    var issued =
        signingOnly().issue("ops-pipeline", List.of("COLLECT", "VIEW"), Duration.ofMinutes(30));

    var jwt = verifyingOnly().decode(issued.token());

    assertThat(jwt.getSubject()).isEqualTo("ops-pipeline");
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("collector-service");
    assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("COLLECT", "VIEW");
    assertThat(jwt.getId()).isNotBlank();
    assertThat(jwt.getExpiresAt())
        .isEqualTo(issued.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
    assertThat(issued.token().split("\\.")).hasSize(3);
  }

  @Test
  void anExpiredTokenIsRejected() {
    var past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
    var issuer =
        new NimbusTokenIssuer(
            "collector-service", "k1", (RSAPrivateKey) SIGN.getPrivate(), null, past);
    var token = issuer.issue("ops", List.of("COLLECT"), Duration.ofMinutes(5)).token();

    assertThatThrownBy(() -> verifyingOnly().decode(token))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void aTokenSignedWithAnotherKeyIsRejected() {
    var forged =
        new NimbusTokenIssuer(
                "collector-service", "k1", (RSAPrivateKey) OTHER_SIGN.getPrivate(), null, clock)
            .issue("ops", List.of("COLLECT"), Duration.ofHours(1))
            .token();

    assertThatThrownBy(() -> verifyingOnly().decode(forged)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenFromAnotherIssuerIsRejected() {
    var other =
        new NimbusTokenIssuer("someone-else", "k1", (RSAPrivateKey) SIGN.getPrivate(), null, clock)
            .issue("ops", List.of("COLLECT"), Duration.ofHours(1))
            .token();

    assertThatThrownBy(() -> verifyingOnly().decode(other))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void aTamperedPayloadIsRejected() {
    String token = signingOnly().issue("ops", List.of("COLLECT"), Duration.ofHours(1)).token();
    String[] parts = token.split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
    String tampered =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.replace("COLLECT", "ROOTXXX").getBytes());

    assertThatThrownBy(() -> verifyingOnly().decode(parts[0] + "." + tampered + "." + parts[2]))
        .isInstanceOf(BadJwtException.class);
  }

  @Test
  void anUnsignedTokenWithAlgNoneIsRejected() {
    String header =
        Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"iss\":\"collector-service\",\"sub\":\"x\",\"roles\":[\"COLLECT\"],\"exp\":"
                        + (Instant.now().getEpochSecond() + 3600)
                        + "}")
                    .getBytes());

    assertThatThrownBy(() -> verifyingOnly().decode(header + "." + payload + "."))
        .isInstanceOf(BadJwtException.class);
  }

  @Test
  void aTokenSignedWithAnotherAlgorithmIsRejected() throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("collector-service")
            .subject("x")
            .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
            .build();
    var hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    hs.sign(new com.nimbusds.jose.crypto.MACSigner(new byte[32]));

    assertThatThrownBy(() -> verifyingOnly().decode(hs.serialize()))
        .isInstanceOf(BadJwtException.class);
    var rs512 = new SignedJWT(new JWSHeader(JWSAlgorithm.RS512), claims);
    rs512.sign(new RSASSASigner(SIGN.getPrivate()));
    assertThatThrownBy(() -> verifyingOnly().decode(rs512.serialize()))
        .isInstanceOf(BadJwtException.class);
  }

  @Test
  void anEncryptedTokenHidesItsClaimsAndStillVerifies() {
    var issued = signingAndEncrypting().issue("ops", List.of("COLLECT"), Duration.ofHours(1));

    assertThat(issued.token().split("\\.")).hasSize(5); // a JWE
    assertThat(issued.token()).doesNotContain("COLLECT");
    String firstPart = new String(Base64.getUrlDecoder().decode(issued.token().split("\\.")[0]));
    assertThat(firstPart).contains("RSA-OAEP-256").contains("A256GCM").contains("\"cty\":\"JWT\"");

    var jwt = verifyingAndDecrypting().decode(issued.token());

    assertThat(jwt.getSubject()).isEqualTo("ops");
    assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("COLLECT");
  }

  @Test
  void whenEncryptionIsOnAPlainSignedTokenIsRejected() {
    String plain = signingOnly().issue("ops", List.of("COLLECT"), Duration.ofHours(1)).token();

    assertThatThrownBy(() -> verifyingAndDecrypting().decode(plain))
        .isInstanceOf(BadJwtException.class)
        .hasMessageContaining("encrypted");
  }

  @Test
  void whenEncryptionIsOffAnEncryptedTokenIsRejected() {
    String encrypted =
        signingAndEncrypting().issue("ops", List.of("COLLECT"), Duration.ofHours(1)).token();

    assertThatThrownBy(() -> verifyingOnly().decode(encrypted)).isInstanceOf(BadJwtException.class);
  }

  @Test
  void anEncryptedTokenForAnotherEncryptionKeyIsRejected() {
    var wrongKey =
        new NimbusTokenIssuer(
            "collector-service",
            "k1",
            (RSAPrivateKey) SIGN.getPrivate(),
            (RSAPublicKey) OTHER_ENCRYPT.getPublic(),
            clock);
    String token = wrongKey.issue("ops", List.of("COLLECT"), Duration.ofHours(1)).token();

    assertThatThrownBy(() -> verifyingAndDecrypting().decode(token))
        .isInstanceOf(BadJwtException.class);
  }

  @Test
  void anEncryptedTokenWhoseInnerSignatureIsForgedIsRejected() {
    var forger =
        new NimbusTokenIssuer(
            "collector-service",
            "k1",
            (RSAPrivateKey) OTHER_SIGN.getPrivate(),
            (RSAPublicKey) ENCRYPT.getPublic(),
            clock);
    // anyone can encrypt to the public encryption key, so the signature inside is what matters
    String token = forger.issue("ops", List.of("COLLECT"), Duration.ofHours(1)).token();

    assertThatThrownBy(() -> verifyingAndDecrypting().decode(token))
        .isInstanceOf(BadJwtException.class);
  }
}
