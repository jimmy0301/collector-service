package io.cmdb.collector.inventory.domain.k8s;

import java.time.Instant;

/**
 * A K8s Event. One row per event uid: when Kubernetes updates an Event (count, lastTimestamp), the
 * same row is updated. observedAt is the time the Collector fetched it, used to keep old data from
 * overwriting new data.
 */
public record K8sEventSnapshot(
    String clusterExternalId,
    String eventUid,
    String namespace,
    String involvedKind,
    String involvedName,
    String involvedUid,
    String eventType,
    String reason,
    String message,
    int count,
    Instant firstSeenAt,
    Instant lastSeenAt,
    Instant observedAt) {}
