package io.cmdb.collector.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.security.domain.InvalidTokenRequestException;
import io.cmdb.collector.security.domain.IssuedToken;
import io.cmdb.collector.security.domain.TokenIssuer;
import io.cmdb.collector.security.domain.TokenPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

  record Issued(String subject, List<String> roles, Duration lifetime) {}

  final List<Issued> issued = new ArrayList<>();
  final TokenIssuer issuer =
      (subject, roles, lifetime) -> {
        issued.add(new Issued(subject, roles, lifetime));
        return new IssuedToken(
            "t", subject, roles, Instant.parse("2026-11-15T00:00:00Z").plus(lifetime));
      };
  final TokenService service =
      new TokenService(
          issuer,
          new TokenPolicy(Set.of("COLLECT", "VIEW"), Duration.ofHours(1), Duration.ofHours(24)));

  @Test
  void issuesWithThePolicyDefaultLifetimeWhenNoneIsAsked() {
    var token = service.issue("ops", List.of("COLLECT"), null);

    assertThat(issued).containsExactly(new Issued("ops", List.of("COLLECT"), Duration.ofHours(1)));
    assertThat(token.subject()).isEqualTo("ops");
  }

  @Test
  void issuesWithTheAskedLifetimeAndTrimsTheSubject() {
    service.issue("  ops  ", List.of("COLLECT", "VIEW"), 600L);

    assertThat(issued)
        .containsExactly(new Issued("ops", List.of("COLLECT", "VIEW"), Duration.ofMinutes(10)));
  }

  @Test
  void rejectsABlankSubject() {
    assertThatThrownBy(() -> service.issue(" ", List.of("COLLECT"), null))
        .isInstanceOf(InvalidTokenRequestException.class)
        .hasMessageContaining("subject");
    assertThatThrownBy(() -> service.issue(null, List.of("COLLECT"), null))
        .isInstanceOf(InvalidTokenRequestException.class);
  }

  @Test
  void rejectsNoRoles() {
    assertThatThrownBy(() -> service.issue("ops", List.of(), null))
        .isInstanceOf(InvalidTokenRequestException.class)
        .hasMessageContaining("role");
    assertThatThrownBy(() -> service.issue("ops", null, null))
        .isInstanceOf(InvalidTokenRequestException.class);
  }

  @Test
  void rejectsARoleThePolicyDoesNotAllow() {
    assertThatThrownBy(() -> service.issue("ops", List.of("COLLECT", "ROOT"), null))
        .isInstanceOf(InvalidTokenRequestException.class)
        .hasMessageContaining("ROOT");
    assertThat(issued).isEmpty();
  }

  @Test
  void rejectsALifetimeThatIsNotPositiveOrOverTheMaximum() {
    assertThatThrownBy(() -> service.issue("ops", List.of("COLLECT"), 0L))
        .isInstanceOf(InvalidTokenRequestException.class);
    assertThatThrownBy(() -> service.issue("ops", List.of("COLLECT"), -5L))
        .isInstanceOf(InvalidTokenRequestException.class);
    assertThatThrownBy(() -> service.issue("ops", List.of("COLLECT"), 24 * 3600 + 1L))
        .isInstanceOf(InvalidTokenRequestException.class)
        .hasMessageContaining("maximum");
    service.issue("ops", List.of("COLLECT"), 24 * 3600L); // exactly the maximum is fine
    assertThat(issued).hasSize(1);
  }

  @Test
  void aPolicyWhoseDefaultExceedsTheMaximumIsRefused() {
    assertThatThrownBy(
            () -> new TokenPolicy(Set.of("COLLECT"), Duration.ofHours(2), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
