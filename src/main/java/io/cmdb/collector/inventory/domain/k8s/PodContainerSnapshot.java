package io.cmdb.collector.inventory.domain.k8s;

import java.time.Instant;

/** The actual state of one container in a Pod (including sidecars and init containers). */
public record PodContainerSnapshot(
    String containerName,
    boolean init,
    String image,
    String imageId,
    String imageDigest,
    Boolean ready,
    int restartCount,
    String state,
    String stateReason,
    String lastTerminatedReason,
    Integer lastExitCode,
    Instant lastTerminatedAt) {}
