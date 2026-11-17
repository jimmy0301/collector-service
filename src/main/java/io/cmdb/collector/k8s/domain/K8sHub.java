package io.cmdb.collector.k8s.domain;

import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * K8s integration service (read-only). Returns native Kubernetes JSON (see INTEGRATION_API_SPEC.md
 * chapter 5).
 */
public interface K8sHub {

  List<String> sites();

  /** Full site information; fields that are not available are null. By default only the code. */
  default List<Site> siteDetails() {
    return sites().stream().map(code -> new Site(code, null, null)).toList();
  }

  List<Cluster> clusters(String site);

  /**
   * Lists one kind of resource in one namespace, handling pagination (limit / continue) and handing
   * each item to the consumer.
   *
   * @param fieldSelector may be null, for example {@code type=Warning}
   */
  void list(
      String site,
      String clusterId,
      ResourceKind kind,
      String namespace,
      String fieldSelector,
      Consumer<JsonNode> consumer);
}
