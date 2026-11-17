package io.cmdb.collector.argo.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Argo hub (read-only). Returns native Argo CD JSON (see INTEGRATION_API_SPEC.md chapter 4). */
public interface ArgoHub {

  List<String> sites();

  /** Full site information; fields that are not available are null. By default only the code. */
  default List<Site> siteDetails() {
    return sites().stream().map(code -> new Site(code, null, null)).toList();
  }

  /** GET /api/v1/applications (including items[]) */
  JsonNode applications(String site);

  /** GET /api/v1/applications/{name}/resource-tree */
  JsonNode resourceTree(String site, String appName, String appNamespace, String project);

  /** GET /api/v1/clusters */
  JsonNode clusters(String site);
}
