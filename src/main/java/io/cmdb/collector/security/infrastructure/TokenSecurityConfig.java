package io.cmdb.collector.security.infrastructure;

import io.cmdb.collector.security.domain.TokenIssuer;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Filter chain of the token issuing API ({@code /auth/**}): only the holder of the admin token
 * (collector.security.admin-token) may ask for tokens. Without an admin token, or without a signing
 * key to issue with, the API is switched off: every request answers 404.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class TokenSecurityConfig {

  private static final String ADMIN = "ROLE_TOKEN_ADMIN";

  @Bean
  @Order(2)
  SecurityFilterChain tokenSecurityFilterChain(
      HttpSecurity http, SecurityProperties properties, ObjectProvider<TokenIssuer> issuer)
      throws Exception {
    boolean enabled =
        properties.adminToken() != null
            && !properties.adminToken().isBlank()
            && issuer.getIfAvailable() != null;
    http.securityMatcher("/auth", "/auth/**")
        .csrf(AbstractHttpConfigurer::disable) // bearer token, no cookies to forge
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests -> {
              if (enabled) {
                requests.anyRequest().hasAuthority(ADMIN);
              } else {
                requests.anyRequest().denyAll();
              }
            })
        .oauth2ResourceServer(
            server -> {
              server.opaqueToken(
                  token ->
                      token.introspector(
                          new StaticTokenIntrospector(properties.adminToken(), ADMIN)));
              if (!enabled) {
                server.authenticationEntryPoint(
                    (request, response, exception) ->
                        response.setStatus(HttpServletResponse.SC_NOT_FOUND));
              }
            });
    if (!enabled) {
      http.exceptionHandling(
          handling ->
              handling
                  .authenticationEntryPoint(
                      (request, response, exception) ->
                          response.setStatus(HttpServletResponse.SC_NOT_FOUND))
                  .accessDeniedHandler(
                      (request, response, exception) ->
                          response.setStatus(HttpServletResponse.SC_NOT_FOUND)));
    }
    return http.build();
  }
}
