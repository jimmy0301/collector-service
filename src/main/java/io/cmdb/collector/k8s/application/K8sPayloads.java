package io.cmdb.collector.k8s.application;

import io.cmdb.collector.k8s.domain.Cluster;
import io.cmdb.collector.k8s.domain.ResourceKind;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Which fields of the native K8s JSON to keep, plus site / cluster identity. */
final class K8sPayloads {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final String[] POD_METADATA = {
    "uid",
    "name",
    "namespace",
    "labels",
    "annotations",
    "ownerReferences",
    "resourceVersion",
    "deletionTimestamp",
    "creationTimestamp"
  };
  private static final String[] POD_STATUS = {
    "phase", "containerStatuses", "initContainerStatuses"
  };

  private K8sPayloads() {}

  /**
   * Pod status changes often, and the CMDB only needs the container-level imageID, restartCount and
   * state (INTEGRATION_API_SPEC.md 5.3), so the full spec and status are not kept. Deployment,
   * ReplicaSet and Event are kept whole (redaction is done by RawEventRecorder).
   */
  static ObjectNode object(ResourceKind kind, JsonNode item) {
    ObjectNode out;
    if (kind == ResourceKind.POD) {
      out = F.objectNode();
      out.set("metadata", pick(item.path("metadata"), POD_METADATA));
      out.set("status", pick(item.path("status"), POD_STATUS));
    } else {
      out = (ObjectNode) item.deepCopy();
    }
    // Items returned by list may lack kind / apiVersion; add them
    out.put("kind", kind.kind());
    out.put("apiVersion", kind.apiVersion());
    return out;
  }

  static ObjectNode envelope(String site, Cluster cluster, Instant fetchedAt, JsonNode object) {
    ObjectNode out = F.objectNode();
    out.put("site", site);
    ObjectNode c = out.putObject("cluster");
    c.put("id", cluster.id());
    c.put("name", cluster.name());
    out.put("fetchedAt", fetchedAt.toString());
    out.set("object", object);
    return out;
  }

  /**
   * Excludes fetchedAt: the fingerprint reflects only "which uids exist". The caller adds
   * fetchedAt.
   */
  static ObjectNode resourceList(
      String site,
      Cluster cluster,
      String namespace,
      ResourceKind kind,
      Iterable<String> sortedUids) {
    ObjectNode out = F.objectNode();
    out.put("site", site);
    ObjectNode c = out.putObject("cluster");
    c.put("id", cluster.id());
    c.put("name", cluster.name());
    out.put("namespace", namespace);
    out.put("kind", kind.kind());
    var uids = out.putArray("uids");
    sortedUids.forEach(uids::add);
    return out;
  }

  static ObjectNode clusters(Iterable<Cluster> clusters) {
    ObjectNode out = F.objectNode();
    var items = out.putArray("items");
    for (Cluster c : clusters) {
      ObjectNode n = items.addObject();
      n.put("id", c.id());
      n.put("name", c.name());
      n.put("server", c.server());
    }
    return out;
  }

  private static ObjectNode pick(JsonNode source, String[] fields) {
    ObjectNode out = F.objectNode();
    for (String f : fields) {
      if (source.has(f)) {
        out.set(f, source.get(f).deepCopy());
      }
    }
    return out;
  }
}
