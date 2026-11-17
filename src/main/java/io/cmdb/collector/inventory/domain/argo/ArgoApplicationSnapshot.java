package io.cmdb.collector.inventory.domain.argo;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** One Argo CD Application as reported by a site's Argo (see argo_applications in V1__init.sql). */
public record ArgoApplicationSnapshot(
    String site,
    String uid,
    String name,
    String project,
    String destinationServer,
    String destinationName,
    String destinationNamespace,
    JsonNode sources,
    String syncStatus,
    String healthStatus,
    boolean autoSync,
    JsonNode syncRevisions,
    Instant observedAt) {}
