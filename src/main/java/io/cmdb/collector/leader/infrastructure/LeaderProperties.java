package io.cmdb.collector.leader.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("collector.leader-election")
record LeaderProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("default") String namespace,
    /** Defaults to the Pod name (HOSTNAME). */
    String identity,
    @DefaultValue("15s") Duration leaseDuration,
    @DefaultValue("10s") Duration renewDeadline,
    @DefaultValue("2s") Duration retryPeriod) {}
