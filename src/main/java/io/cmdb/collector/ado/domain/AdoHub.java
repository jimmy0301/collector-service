package io.cmdb.collector.ado.domain;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Azure DevOps Release (read-only). Returns native Azure DevOps REST JSON (Release REST 7.1,
 * vsrm.dev.azure.com).
 */
public interface AdoHub {

  /**
   * GET release/deployments with {@code minModifiedTime=since}, ascending by modification time,
   * following every page. Each item is the native deployment object.
   */
  List<JsonNode> deploymentsModifiedSince(String project, Instant since);

  /** GET release/releases/{releaseId} */
  JsonNode release(String project, String releaseId);
}
