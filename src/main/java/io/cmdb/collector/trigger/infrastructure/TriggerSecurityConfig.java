package io.cmdb.collector.trigger.infrastructure;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Filter chain of the on-demand collect API ({@code /collect/**}): a JWT, issued by this collector
 * (POST /auth/token), that carries the role {@code COLLECT}. Missing, wrong or expired token: 401
 * with {@code WWW-Authenticate: Bearer}; a valid token without the role: 403. Without token keys
 * configured (no {@link JwtDecoder}) the API is switched off: every request answers 404.
 */
@Configuration
class TriggerSecurityConfig {

  static final String ROLE = "COLLECT";

  @Bean
  @Order(1)
  SecurityFilterChain triggerSecurityFilterChain(
      HttpSecurity http,
      ObjectProvider<JwtDecoder> decoder,
      ObjectProvider<JwtAuthenticationConverter> converter) {
    boolean enabled = decoder.getIfAvailable() != null;
    http.securityMatcher("/collect", "/collect/**")
        .csrf(AbstractHttpConfigurer::disable) // bearer token, no cookies to forge
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests -> {
              if (enabled) {
                requests.anyRequest().hasRole(ROLE);
              } else {
                requests.anyRequest().denyAll();
              }
            });
    if (enabled) {
      http.oauth2ResourceServer(
          server ->
              server.jwt(
                  jwt -> {
                    jwt.decoder(decoder.getObject());
                    converter.ifAvailable(jwt::jwtAuthenticationConverter);
                  }));
    }
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
