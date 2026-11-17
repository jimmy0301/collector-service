package io.cmdb.collector.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.support.TestKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

class PemKeysTest {

  @TempDir Path dir;

  @Test
  void readsTheKeysOpensslWrites() {
    var pair = TestKeys.rsa(2048);

    var privateKey =
        PemKeys.privateKey(new FileSystemResource(TestKeys.writePrivate(dir, "k.key", pair)));
    var publicKey =
        PemKeys.publicKey(new FileSystemResource(TestKeys.writePublic(dir, "k.pub", pair)));

    assertThat(privateKey.getModulus()).isEqualTo(publicKey.getModulus());
  }

  @Test
  void refusesAKeyShorterThan2048Bits() {
    var weak = TestKeys.rsa(1024);

    assertThatThrownBy(
            () ->
                PemKeys.privateKey(
                    new FileSystemResource(TestKeys.writePrivate(dir, "w.key", weak))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("2048");
    assertThatThrownBy(
            () ->
                PemKeys.publicKey(new FileSystemResource(TestKeys.writePublic(dir, "w.pub", weak))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("2048");
  }

  @Test
  void aPkcs1PrivateKeyGetsAHelpfulMessage() throws Exception {
    Path file =
        Files.writeString(
            dir.resolve("old.key"),
            "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

    assertThatThrownBy(() -> PemKeys.privateKey(new FileSystemResource(file)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PKCS#1")
        .hasMessageContaining("openssl pkcs8");
  }

  @Test
  void aFileThatIsNotPemOrNotThere() throws Exception {
    Path junk = Files.writeString(dir.resolve("junk"), "hello");

    assertThatThrownBy(() -> PemKeys.publicKey(new FileSystemResource(junk)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLIC KEY");
    assertThatThrownBy(() -> PemKeys.publicKey(new FileSystemResource(dir.resolve("missing"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot read");
  }

  @Test
  void aMatchingSigningPairPasses() {
    var pair = TestKeys.rsa(2048);

    PemKeys.requireSigningPair(
        (java.security.interfaces.RSAPrivateKey) pair.getPrivate(),
        (java.security.interfaces.RSAPublicKey) pair.getPublic(),
        "test");
  }

  @Test
  void aSigningPrivateKeyWithSomeoneElsesPublicKeyIsRejected() {
    var mine = TestKeys.rsa(2048);
    var other = TestKeys.rsa(2048);

    assertThatThrownBy(
            () ->
                PemKeys.requireSigningPair(
                    (java.security.interfaces.RSAPrivateKey) mine.getPrivate(),
                    (java.security.interfaces.RSAPublicKey) other.getPublic(),
                    "collector.security.jwt.signing-*"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collector.security.jwt.signing-*")
        .hasMessageContaining("not a pair");
  }

  @Test
  void aMatchingEncryptionPairPassesAndAMismatchIsRejected() {
    var mine = TestKeys.rsa(2048);
    var other = TestKeys.rsa(2048);

    PemKeys.requireEncryptionPair(
        (java.security.interfaces.RSAPrivateKey) mine.getPrivate(),
        (java.security.interfaces.RSAPublicKey) mine.getPublic(),
        "test");
    assertThatThrownBy(
            () ->
                PemKeys.requireEncryptionPair(
                    (java.security.interfaces.RSAPrivateKey) mine.getPrivate(),
                    (java.security.interfaces.RSAPublicKey) other.getPublic(),
                    "collector.security.jwt.encryption-*"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("encryption-*")
        .hasMessageContaining("not a pair");
  }
}
