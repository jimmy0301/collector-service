package io.cmdb.collector.argo.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("collector.argo")
record ArgoProperties(
    /* Argo hub URL, for example https://argo-hub.corp.example */
    String baseUrl,
    /* Read-only service-to-service token (injected from a Secret). */
    String token,
    /* Site codes to poll; when empty, GET /sites is called. */
    @DefaultValue List<String> sites,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout) {}
