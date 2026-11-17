package io.cmdb.collector.inventory.domain.k8s;

import java.util.List;

/**
 * The key state of a workload resource at one observation; a change in any field starts a new
 * history row. Fields that do not apply to the kind are null (phase for a Deployment, replicas for
 * a Pod).
 *
 * @param images sorted by container name, so the same state is always equal
 */
public record K8sResourceState(
    String phase,
    int restartCount,
    Integer replicas,
    Integer readyReplicas,
    List<ContainerImage> images,
    boolean terminating) {

  /** digest is null where the source does not report it (Deployment / ReplicaSet specs). */
  public record ContainerImage(String name, String image, String digest) {}
}
