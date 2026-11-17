package io.cmdb.collector.inventory.domain.k8s;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Identity and state of a Deployment, ReplicaSet or Pod. spec and status exist only for Deployment
 * and ReplicaSet (null for Pod, whose state is normalized into k8s_pod_containers). observedAt is
 * the time the Collector fetched the data, used to keep old data from overwriting new data.
 */
public record K8sResourceSnapshot(
    String clusterExternalId,
    String resourceUid,
    String namespace,
    String kind,
    String name,
    String apiVersion,
    int restartCount,
    String phase,
    JsonNode labels,
    JsonNode annotations,
    JsonNode spec,
    JsonNode status,
    Instant observedAt,
    Instant deletedAt) {}
