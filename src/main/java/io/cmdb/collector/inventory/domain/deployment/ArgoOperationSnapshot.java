package io.cmdb.collector.inventory.domain.deployment;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * One sync operation of an Argo CD Application (status.operationState) as the Argo-side fact of a
 * deployment.
 *
 * @param operationKey {@code {application uid}:{operationState.startedAt}}, the idempotency key
 * @param status RUNNING / SUCCEEDED / FAILED (derived from the Argo phase)
 * @param gitRevision the manifest repo commit that was synced (the first one for a multi-source
 *     Application; all of them are in {@code metadata})
 * @param metadata phase, initiated_by, argo_operation_info, revisions
 */
public record ArgoOperationSnapshot(
    String operationKey,
    String status,
    String statusMessage,
    String gitRevision,
    String syncStatus,
    String healthStatus,
    Instant startedAt,
    Instant finishedAt,
    JsonNode metadata) {}
