package io.cmdb.collector.security.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The fallback filter chain, used by every request that no module's own chain (a chain with a
 * {@code securityMatcher}, such as the trigger API's) claims: the K8s probes, the Prometheus scrape
 * and the error page stay open, everything else is denied. A new endpoint is therefore closed until
 * someone opens it on purpose.
 */
@Configuration
class DefaultSecurityConfig {

  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/info", "/actuator/prometheus")
                    .permitAll()
                    // When a handler fails with a status (404 for an unknown job, 409 for a busy
                    // source) the container dispatches to /error; denying it would turn every such
                    // status into 403
                    .requestMatchers("/error")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .csrf(AbstractHttpConfigurer::disable) // no browser session, no cookies to forge
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }
}
