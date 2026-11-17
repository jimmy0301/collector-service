package io.cmdb.collector.ado.application;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Whitelists what is kept from the native Azure DevOps objects. A release also carries variables
 * (including non-secret values that may still be sensitive), variable groups, and the full
 * environment definitions; none of that is needed, so it is never stored. Anything not listed here
 * is dropped.
 */
final class AdoPayloads {

  private AdoPayloads() {}

  /** {project, fetchedAt, resource}: the same envelope shape the Argo collector uses. */
  static ObjectNode envelope(String project, Instant fetchedAt, ObjectNode resource) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("project", project);
    body.put("fetchedAt", fetchedAt.toString());
    body.set("resource", resource);
    return body;
  }

  static ObjectNode deployment(JsonNode d) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    copy(
        d,
        out,
        "id",
        "attempt",
        "reason",
        "deploymentStatus",
        "operationStatus",
        "definitionEnvironmentId");
    copy(d, out, "queuedOn", "startedOn", "completedOn", "lastModifiedOn");
    idAndName(d, out, "release");
    idAndName(d, out, "releaseDefinition");
    idAndName(d, out, "releaseEnvironment");
    displayName(d, out, "requestedBy");
    displayName(d, out, "requestedFor");
    return out;
  }

  static ObjectNode release(JsonNode r) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    copy(r, out, "id", "name", "status", "reason", "createdOn", "modifiedOn");
    idAndName(r, out, "releaseDefinition");
    displayName(r, out, "createdBy");
    var artifacts = out.putArray("artifacts");
    for (JsonNode a : r.path("artifacts")) {
      ObjectNode artifact = artifacts.addObject();
      copy(a, artifact, "alias", "type", "isPrimary");
      JsonNode ref = a.path("definitionReference");
      ObjectNode kept = artifact.putObject("definitionReference");
      for (String key : new String[] {"version", "branch", "definition"}) {
        idAndName(ref, kept, key);
      }
    }
    return out;
  }

  private static void copy(JsonNode from, ObjectNode to, String... fields) {
    for (String f : fields) {
      JsonNode v = from.get(f);
      if (v != null && !v.isNull()) {
        to.set(f, v);
      }
    }
  }

  private static void idAndName(JsonNode from, ObjectNode to, String field) {
    JsonNode node = from.get(field);
    if (node == null || !node.isObject()) {
      return;
    }
    ObjectNode kept = to.putObject(field);
    copy(node, kept, "id", "name");
  }

  private static void displayName(JsonNode from, ObjectNode to, String field) {
    JsonNode node = from.get(field);
    if (node == null || !node.isObject()) {
      return;
    }
    copy(node, to.putObject(field), "displayName");
  }
}
