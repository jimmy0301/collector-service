package io.cmdb.collector.inventory.domain.cluster;

/** Write side of clusters (see {@link SiteStore}). */
public interface ClusterStore {

  /**
   * Inserts or updates name, site and server (does not touch manually maintained columns such as
   * code and environment).
   */
  void upsert(ClusterSnapshot cluster);

  /**
   * Creates only if absent (used when a workload event arrives first and the cluster list has not
   * been processed yet).
   */
  void ensureExists(ClusterSnapshot cluster);

  /**
   * Backfills argo_cluster_name from the Argo cluster list: matched by argo_server_url within the
   * same site (case and trailing slash ignored).
   *
   * @return the number of updated rows (0 if no cluster matches or the name is already the same)
   */
  int setArgoClusterName(String siteCode, String argoServerUrl, String argoClusterName);
}
