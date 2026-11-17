package io.cmdb.collector.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.security.domain.TokenIssuer;
import io.cmdb.collector.security.domain.TokenPolicy;
import io.cmdb.collector.support.TestKeys;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * How the token keys are wired from properties, without starting the application: the beans exist
 * only when a signing key is set, an issued token verifies, and half-set keys fail at startup.
 */
class JwtConfigTest {

  @TempDir Path dir;

  Path signKey;
  Path signPub;
  Path encKey;
  Path encPub;

  @Configuration
  @EnableConfigurationProperties(JwtProperties.class)
  static class Props {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  ApplicationContextRunner runner() {
    return new ApplicationContextRunner().withUserConfiguration(Props.class, JwtConfig.class);
  }

  @BeforeEach
  void keys() {
    KeyPair sign = TestKeys.rsa(2048);
    KeyPair enc = TestKeys.rsa(2048);
    signKey = TestKeys.writePrivate(dir, "sign.key", sign);
    signPub = TestKeys.writePublic(dir, "sign.pub", sign);
    encKey = TestKeys.writePrivate(dir, "enc.key", enc);
    encPub = TestKeys.writePublic(dir, "enc.pub", enc);
  }

  static String file(Path p) {
    return "file:" + p;
  }

  @Test
  void withNoSigningKeyThereAreNoBeansAtAll() {
    runner()
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(TokenIssuer.class);
              assertThat(context).doesNotHaveBean(JwtDecoder.class);
            });
  }

  @Test
  void keysThatAreSetButNotEnabledStillGiveNoBeans() {
    runner()
        .withPropertyValues(
            "collector.security.jwt.signing-private-key=" + file(signKey),
            "collector.security.jwt.signing-public-key=" + file(signPub))
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(TokenIssuer.class);
              assertThat(context).doesNotHaveBean(JwtDecoder.class);
            });
  }

  @Test
  void enabledWithoutAnyKeyFailsAtStartup() {
    runner()
        .withPropertyValues("collector.security.jwt.enabled=true")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "collector.security.jwt.enabled is true, so signing-private-key and signing-public-key are required"));
  }

  @Test
  void blankKeyValuesFromTheDefaultConfigurationCountAsNotSet() {
    // application.yml maps each key to an environment variable that defaults to empty
    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=false",
            "collector.security.jwt.signing-private-key=",
            "collector.security.jwt.signing-public-key=",
            "collector.security.jwt.encryption-private-key=",
            "collector.security.jwt.encryption-public-key=")
        .run(context -> assertThat(context).doesNotHaveBean(TokenIssuer.class));
  }

  @Test
  void aSigningKeyPairGivesAnIssuerAndADecoderThatAgree() {
    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=true",
            "collector.security.jwt.signing-private-key=" + file(signKey),
            "collector.security.jwt.signing-public-key=" + file(signPub))
        .run(
            context -> {
              var issued =
                  context
                      .getBean(TokenIssuer.class)
                      .issue("ops", List.of("COLLECT"), java.time.Duration.ofMinutes(5));
              var jwt = context.getBean(JwtDecoder.class).decode(issued.token());
              assertThat(jwt.getSubject()).isEqualTo("ops");
              assertThat(issued.token().split("\\.")).hasSize(3);
              assertThat(context.getBean(TokenPolicy.class).allowedRoles())
                  .containsExactly("COLLECT");
            });
  }

  @Test
  void anEncryptionKeyPairMakesTokensEncrypted() {
    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=true",
            "collector.security.jwt.signing-private-key=" + file(signKey),
            "collector.security.jwt.signing-public-key=" + file(signPub),
            "collector.security.jwt.encryption-private-key=" + file(encKey),
            "collector.security.jwt.encryption-public-key=" + file(encPub),
            "collector.security.jwt.allowed-roles=COLLECT,VIEW")
        .run(
            context -> {
              var issued =
                  context
                      .getBean(TokenIssuer.class)
                      .issue("ops", List.of("VIEW"), java.time.Duration.ofMinutes(5));
              assertThat(issued.token().split("\\.")).hasSize(5);
              assertThat(context.getBean(JwtDecoder.class).decode(issued.token()).getSubject())
                  .isEqualTo("ops");
              assertThat(context.getBean(TokenPolicy.class).allowedRoles())
                  .containsExactlyInAnyOrder("COLLECT", "VIEW");
            });
  }

  @Test
  void aSigningPrivateKeyWithoutItsPublicKeyFailsAtStartup() {
    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=true",
            "collector.security.jwt.signing-private-key=" + file(signKey))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "collector.security.jwt.enabled is true, so signing-private-key and signing-public-key are required"));
  }

  @Test
  void halfAnEncryptionKeyPairFailsAtStartup() {
    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=true",
            "collector.security.jwt.signing-private-key=" + file(signKey),
            "collector.security.jwt.signing-public-key=" + file(signPub),
            "collector.security.jwt.encryption-public-key=" + file(encPub))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "set both collector.security.jwt.encryption-public-key and encryption-private-key, or neither"));
  }

  @Test
  void aMissingKeyFileFailsAtStartupNamingTheFile() {
    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=true",
            "collector.security.jwt.signing-private-key=file:/no/such/sign.key",
            "collector.security.jwt.signing-public-key=" + file(signPub))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("/no/such/sign.key"));
  }

  @Test
  void aSigningPublicKeyThatIsNotThePartnerOfThePrivateKeyFailsAtStartup() {
    Path strangerPub = TestKeys.writePublic(dir, "stranger.pub", TestKeys.rsa(2048));

    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=true",
            "collector.security.jwt.signing-private-key=" + file(signKey),
            "collector.security.jwt.signing-public-key=" + file(strangerPub))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("signing-*")
                    .hasMessageContaining("not a pair"));
  }

  @Test
  void anEncryptionPublicKeyThatIsNotThePartnerOfThePrivateKeyFailsAtStartup() {
    Path strangerPub = TestKeys.writePublic(dir, "stranger-enc.pub", TestKeys.rsa(2048));

    runner()
        .withPropertyValues(
            "collector.security.jwt.enabled=true",
            "collector.security.jwt.signing-private-key=" + file(signKey),
            "collector.security.jwt.signing-public-key=" + file(signPub),
            "collector.security.jwt.encryption-private-key=" + file(encKey),
            "collector.security.jwt.encryption-public-key=" + file(strangerPub))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("encryption-*")
                    .hasMessageContaining("not a pair"));
  }
}
