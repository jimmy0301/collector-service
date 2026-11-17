package io.cmdb.collector.k8s.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("collector.k8s")
record K8sProperties(
    /* K8s integration service URL. */
    String baseUrl,
    /** Read-only service-to-service token (injected from a Secret). */
    String token,
    /* Site codes to poll; when empty, GET /sites is called. */
    @DefaultValue List<String> sites,
    /* limit of the list call. */
    @DefaultValue("500") int pageSize,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout) {}
