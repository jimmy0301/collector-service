package io.cmdb.collector.security.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import org.springframework.core.io.Resource;

/**
 * Reads RSA keys from PEM files: PKCS#8 private keys and X.509 (SubjectPublicKeyInfo) public keys.
 */
final class PemKeys {

  static final int MIN_BITS = 2048;

  private PemKeys() {}

  static RSAPrivateKey privateKey(Resource resource) {
    byte[] der = der(resource, "PRIVATE KEY");
    try {
      var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      return checked((RSAPrivateKey) key, resource);
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException("not an RSA PKCS#8 private key: " + resource, e);
    }
  }

  static RSAPublicKey publicKey(Resource resource) {
    byte[] der = der(resource, "PUBLIC KEY");
    try {
      var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      return checked((RSAPublicKey) key, resource);
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalStateException("not an RSA public key: " + resource, e);
    }
  }

  /**
   * Fails unless the two keys are a pair: something signed with the private key must verify with
   * the public key. A mismatch would otherwise start fine and then reject every token issued.
   */
  static void requireSigningPair(RSAPrivateKey privateKey, RSAPublicKey publicKey, String what) {
    byte[] probe = "collector key pair check".getBytes(StandardCharsets.UTF_8);
    boolean pair;
    try {
      var signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey);
      signer.update(probe);
      byte[] signature = signer.sign();
      var verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(probe);
      pair = verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      pair = false;
    }
    if (!pair) {
      throw new IllegalStateException(
          what
              + ": the private key and the public key are not a pair (a signature made with the"
              + " private key does not verify with the public key)");
    }
  }

  /** Fails unless what is encrypted with the public key decrypts with the private key. */
  static void requireEncryptionPair(RSAPrivateKey privateKey, RSAPublicKey publicKey, String what) {
    byte[] probe = "collector key pair check".getBytes(StandardCharsets.UTF_8);
    boolean pair;
    try {
      var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      cipher.init(Cipher.ENCRYPT_MODE, publicKey);
      byte[] encrypted = cipher.doFinal(probe);
      cipher.init(Cipher.DECRYPT_MODE, privateKey);
      pair = MessageDigest.isEqual(probe, cipher.doFinal(encrypted));
    } catch (GeneralSecurityException e) {
      pair = false;
    }
    if (!pair) {
      throw new IllegalStateException(
          what
              + ": the private key and the public key are not a pair (what the public key"
              + " encrypts cannot be decrypted with the private key)");
    }
  }

  private static <K extends java.security.interfaces.RSAKey> K checked(K key, Resource resource) {
    if (key.getModulus().bitLength() < MIN_BITS) {
      throw new IllegalStateException("RSA key is shorter than " + MIN_BITS + " bits: " + resource);
    }
    return key;
  }

  /** The Base64 body between "-----BEGIN label-----" and "-----END label-----". */
  private static byte[] der(Resource resource, String label) {
    String pem;
    try (InputStream in = resource.getInputStream()) {
      pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read key file: " + resource, e);
    }
    String begin = "-----BEGIN " + label + "-----";
    String end = "-----END " + label + "-----";
    int from = pem.indexOf(begin);
    int to = pem.indexOf(end);
    if (from < 0 || to < from) {
      throw new IllegalStateException(
          "expected a PEM block \""
              + begin
              + "\" in "
              + resource
              + (pem.contains("BEGIN RSA PRIVATE KEY")
                  ? " (this is a PKCS#1 key: convert it with openssl pkcs8 -topk8 -nocrypt)"
                  : ""));
    }
    String body = pem.substring(from + begin.length(), to).replaceAll("\\s", "");
    return Base64.getDecoder().decode(body);
  }
}
