package io.cmdb.collector.ado.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("collector.ado")
record AdoProperties(
    /* Release host, for example https://vsrm.dev.azure.com */
    @DefaultValue("https://vsrm.dev.azure.com") String baseUrl,
    String organization,
    /* Projects to poll; nothing is polled while this (or the organization) is empty. */
    @DefaultValue List<String> projects,
    /* Read-only Personal Access Token (injected from a Secret); it expires, so it must be rotated. */
    String token,
    @DefaultValue("100") int pageSize,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout) {}
