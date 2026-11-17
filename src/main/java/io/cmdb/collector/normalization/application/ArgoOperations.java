package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.deployment.ArgoOperationSnapshot;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Extracts the sync operation (status.operationState) of an Application. Argo keeps only the latest
 * one, so every Application event carries at most one.
 */
final class ArgoOperations {

  private ArgoOperations() {}

  static Optional<ArgoOperationSnapshot> of(String appUid, JsonNode app) {
    JsonNode state = app.path("status").path("operationState");
    String startedAt = state.path("startedAt").asString("");
    if (!state.isObject() || startedAt.isBlank()) {
      return Optional.empty(); // never synced, or nothing to key the operation by
    }
    String phase = state.path("phase").asString("");
    JsonNode syncResult = state.path("syncResult");
    JsonNode revisions = syncResult.path("revisions");
    String revision =
        revisions.isArray() && !revisions.isEmpty()
            ? revisions.get(0).asString(null)
            : syncResult.path("revision").asString(null);

    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("phase", phase);
    JsonNode initiatedBy = state.path("operation").path("initiatedBy");
    if (initiatedBy.isObject()) {
      ObjectNode who = metadata.putObject("initiated_by");
      who.put("automated", initiatedBy.path("automated").asBoolean(false));
      if (initiatedBy.has("username")) {
        who.put("username", initiatedBy.path("username").asString());
      }
    }
    JsonNode info = state.path("operation").path("info");
    if (info.isArray() && !info.isEmpty()) {
      metadata.set("argo_operation_info", info);
    }
    if (revisions.isArray() && !revisions.isEmpty()) {
      metadata.set("revisions", revisions);
    }

    return Optional.of(
        new ArgoOperationSnapshot(
            appUid + ":" + startedAt,
            status(phase),
            state.path("message").asString(null),
            revision,
            app.path("status").path("sync").path("status").asString(null),
            app.path("status").path("health").path("status").asString(null),
            Payloads.optionalInstant(state.path("startedAt")),
            Payloads.optionalInstant(state.path("finishedAt")),
            metadata));
  }

  /**
   * Argo's OperationPhase: Running, Terminating, Succeeded, Failed, Error. An unknown phase is an
   * error (the event is marked failed and the reason is kept) rather than a guess.
   */
  static String status(String phase) {
    return switch (phase) {
      case "Running", "Terminating" -> "RUNNING";
      case "Succeeded" -> "SUCCEEDED";
      case "Failed", "Error" -> "FAILED";
      default ->
          throw new IllegalArgumentException("unknown Argo operation phase: '" + phase + "'");
    };
  }
}
