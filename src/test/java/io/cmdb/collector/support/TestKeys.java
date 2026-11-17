package io.cmdb.collector.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** RSA key pairs for tests, and PEM files of them like the ones a mounted K8s Secret holds. */
public final class TestKeys {

  private TestKeys() {}

  public static KeyPair rsa(int bits) {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** PKCS#8 private key, as {@code openssl genpkey} writes it. */
  public static Path writePrivate(Path dir, String name, KeyPair pair) {
    return write(dir.resolve(name), "PRIVATE KEY", pair.getPrivate().getEncoded());
  }

  /** X.509 SubjectPublicKeyInfo, as {@code openssl pkey -pubout} writes it. */
  public static Path writePublic(Path dir, String name, KeyPair pair) {
    return write(dir.resolve(name), "PUBLIC KEY", pair.getPublic().getEncoded());
  }

  private static Path write(Path file, String label, byte[] der) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
    try {
      return Files.writeString(
          file, "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
