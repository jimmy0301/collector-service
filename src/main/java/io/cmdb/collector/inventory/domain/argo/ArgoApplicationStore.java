package io.cmdb.collector.inventory.domain.argo;

import java.util.List;

/** Write side of argo_applications and of the Argo columns of k8s_resources. */
public interface ArgoApplicationStore {

  /**
   * Inserts or updates an Application; an older observation never overwrites a newer one and a null
   * never blanks a stored value. {@code cluster_id} is resolved from the registered clusters
   * (destination server → {@code argo_server_url}, destination name → {@code argo_cluster_name},
   * within the site) and stays null when there is no match.
   *
   * @return the Application id
   */
  long upsert(ArgoApplicationSnapshot application);

  /**
   * Makes sure the Application exists (its resource-tree can be processed before the Application
   * is). The stub is overwritten by {@link #upsert}.
   *
   * @return the Application id
   */
  long ensureStub(String site, String uid, String name);

  /**
   * Marks the K8s resources with these uids as managed by the Application and stores Argo's health
   * judgment. A Deployment, ReplicaSet or Pod that K8s has not reported yet gets a placeholder row
   * (when the Application's cluster is known), which the K8s event later overwrites without
   * touching these columns, so the order of the Argo and K8s events does not matter. Other kinds
   * are never created (they are not collected from K8s).
   *
   * @return how many resources were created or changed
   */
  int linkResources(long applicationId, List<ArgoResourceNode> nodes);

  /**
   * Resolves {@code cluster_id} for the Applications of a site that have none yet (the clusters may
   * have been registered after the Application was seen).
   *
   * @return how many Applications were linked
   */
  int linkUnmappedClusters(String site);
}
