package io.cmdb.collector.security.infrastructure;

import io.cmdb.collector.security.application.TokenService;
import io.cmdb.collector.security.domain.InvalidTokenRequestException;
import io.cmdb.collector.security.domain.IssuedToken;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implements the generated {@link AuthApi} (contract: src/main/resources/openapi/auth.yaml). Who
 * may call it is decided by {@link TokenSecurityConfig}; what the token may contain, by {@link
 * TokenService}.
 */
@RestController
@ConditionalOnProperty(prefix = "collector.security.jwt", name = "enabled", havingValue = "true")
class TokenController implements AuthApi {

  private final TokenService tokens;

  TokenController(TokenService tokens) {
    this.tokens = tokens;
  }

  @Override
  public ResponseEntity<IssuedTokenDto> issueToken(IssueTokenRequestDto request) {
    IssuedToken issued;
    try {
      issued =
          tokens.issue(request.getSubject(), request.getRoles(), request.getExpiresInSeconds());
    } catch (InvalidTokenRequestException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.ok(
        new IssuedTokenDto(
            issued.token(),
            "Bearer",
            issued.subject(),
            issued.roles(),
            issued.expiresAt().atOffset(ZoneOffset.UTC)));
  }
}
