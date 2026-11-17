package io.cmdb.collector.security.infrastructure;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.crypto.RSADecrypter;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies the tokens {@link NimbusTokenIssuer} issues: RS256 signature, issuer, and expiry. When
 * an encryption key is configured only encrypted tokens are accepted (they are decrypted first and
 * the signature of the inner token is then verified); otherwise only plain signed tokens are.
 */
class CollectorJwtDecoder implements JwtDecoder {

  private final JwtDecoder signed;
  private final RSAPrivateKey decryptionKey; // null: tokens are not encrypted

  CollectorJwtDecoder(String issuer, RSAPublicKey signingKey, RSAPrivateKey decryptionKey) {
    var decoder =
        NimbusJwtDecoder.withPublicKey(signingKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
    decoder.setJwtValidator(validator);
    this.signed = decoder;
    this.decryptionKey = decryptionKey;
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    boolean looksEncrypted = token.chars().filter(c -> c == '.').count() == 4; // a JWE has 5 parts
    if (decryptionKey == null) {
      if (looksEncrypted) {
        throw new BadJwtException("encrypted tokens are not accepted");
      }
      return signed.decode(token);
    }
    if (!looksEncrypted) {
      throw new BadJwtException("an encrypted token is required");
    }
    return signed.decode(decrypt(token));
  }

  private String decrypt(String token) {
    try {
      JWEObject jwe = JWEObject.parse(token);
      // fixed algorithms: never trust the header to pick a weaker or different scheme
      if (!JWEAlgorithm.RSA_OAEP_256.equals(jwe.getHeader().getAlgorithm())
          || !EncryptionMethod.A256GCM.equals(jwe.getHeader().getEncryptionMethod())) {
        throw new BadJwtException("unsupported encryption");
      }
      jwe.decrypt(new RSADecrypter(decryptionKey));
      var inner = jwe.getPayload().toSignedJWT();
      if (inner == null) {
        throw new BadJwtException("the encrypted content is not a signed token");
      }
      return inner.serialize();
    } catch (ParseException | JOSEException e) {
      throw new BadJwtException("cannot decrypt the token", e);
    }
  }
}
