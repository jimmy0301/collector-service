package io.cmdb.collector.inventory.domain.cluster;

import java.util.Locale;

/**
 * A cluster. externalId is the stable identity provided by the K8s integration service; serverUrl
 * is written the same way as Argo's destination.server.
 */
public record ClusterSnapshot(String siteCode, String externalId, String name, String serverUrl) {

  /**
   * clusters.code is a manually maintained "stable custom ID" (SOURCE_MAPPING.md). When created
   * automatically {@code {site}-{name}} is the default; it can later be changed by hand to the
   * official code, and upsert never overwrites an existing code.
   */
  public String defaultCode() {
    return (siteCode + "-" + name).toLowerCase(Locale.ROOT);
  }
}
