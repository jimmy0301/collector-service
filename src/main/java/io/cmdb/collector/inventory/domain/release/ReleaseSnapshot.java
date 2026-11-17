package io.cmdb.collector.inventory.domain.release;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** One Azure DevOps release as reported by the release REST API (see releases in V1__init.sql). */
public record ReleaseSnapshot(
    String project,
    String externalReleaseId,
    String definitionId,
    String definitionName,
    String name,
    String status,
    String reason,
    String artifactAlias,
    String artifactBuildRef,
    String sourceBranch,
    JsonNode artifacts,
    String createdBy,
    Instant createdAt,
    Instant modifiedAt) {}
