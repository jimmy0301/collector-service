package io.cmdb.collector.argo.application;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shrinks the native Argo JSON to what is landed and adds the site identity. Only chooses fields,
 * does not interpret business meaning.
 */
final class ArgoPayloads {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ArgoPayloads() {}

  /**
   * Removes fields that change on every reconcile (resourceVersion, reconciledAt, observedAt),
   * otherwise the content fingerprint would differ every round; also removes status.resources and
   * syncResult.resources (the full resource list comes from the resource-tree instead, PLAN.md 9.3
   * does not store it).
   */
  static ObjectNode application(JsonNode app) {
    ObjectNode copy = (ObjectNode) app.deepCopy();
    removeFrom(copy.get("metadata"), "resourceVersion");
    JsonNode status = copy.get("status");
    removeFrom(status, "reconciledAt");
    removeFrom(status, "observedAt");
    removeFrom(status, "resources");
    if (status != null && status.get("operationState") != null) {
      removeFrom(status.get("operationState").get("syncResult"), "resources");
    }
    return copy;
  }

  /**
   * Keeps only the fields needed to match K8s resources (uid, kind, name, health, parent-child
   * relationships).
   */
  static ObjectNode resourceTree(JsonNode tree) {
    ObjectNode out = F.objectNode();
    out.set("nodes", projectNodes(tree.path("nodes")));
    out.set("orphanedNodes", projectNodes(tree.path("orphanedNodes")));
    return out;
  }

  private static ArrayNode projectNodes(JsonNode nodes) {
    ArrayNode result = F.arrayNode();
    for (JsonNode n : nodes) {
      ObjectNode p = F.objectNode();
      for (String field :
          new String[] {
            "uid", "group", "version", "kind", "namespace", "name", "health", "parentRefs", "images"
          }) {
        if (n.has(field)) {
          p.set(field, n.get(field).deepCopy());
        }
      }
      result.add(p);
    }
    return result;
  }

  /**
   * A cluster object contains connection settings (possibly credentials), so keep only name and
   * server.
   */
  static ObjectNode cluster(JsonNode c) {
    ObjectNode out = F.objectNode();
    out.set("name", c.path("name").deepCopy());
    out.set("server", c.path("server").deepCopy());
    return out;
  }

  static ObjectNode envelope(String site, Instant fetchedAt, JsonNode resource) {
    ObjectNode out = F.objectNode();
    out.put("site", site);
    out.put("fetchedAt", fetchedAt.toString());
    out.set("resource", resource);
    return out;
  }

  /**
   * The resource-tree event also says which Application it belongs to: the tree itself only lists
   * resources, and the handler needs the Application to link them to.
   */
  static ObjectNode treeEnvelope(
      String site, Instant fetchedAt, String appUid, String appName, JsonNode resource) {
    ObjectNode out = envelope(site, fetchedAt, resource);
    ObjectNode app = F.objectNode();
    app.put("uid", appUid);
    app.put("name", appName);
    out.set("application", app);
    return out;
  }

  private static void removeFrom(JsonNode node, String field) {
    if (node instanceof ObjectNode o) {
      o.remove(field);
    }
  }
}
