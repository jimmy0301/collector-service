package io.cmdb.collector.security.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code adminToken}: the bearer token that may ask POST /auth/token for tokens. Blank (the
 * default) switches token issuing off.
 */
@ConfigurationProperties("collector.security")
record SecurityProperties(String adminToken) {}
