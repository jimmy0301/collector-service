package io.cmdb.collector.inventory.domain.k8s;

import java.time.Instant;
import java.util.Collection;

/**
 * Write side of k8s_resources, k8s_pod_containers and k8s_resource_relationships (see {@link
 * SiteStore}).
 */
public interface K8sResourceStore {

  /**
   * Inserts or updates a resource. It overwrites only when {@code observedAt} is not earlier than
   * the stored value (an old event must not overwrite newer state).
   *
   * @return the id of the resource in k8s_resources (even if nothing was updated because the data
   *     was older)
   */
  long upsert(K8sResourceSnapshot resource);

  /**
   * When a child resource is processed before its parent, first creates a placeholder row for the
   * parent (observed_at is epoch); the parent's own event overwrites it later.
   *
   * @return the id of the parent resource
   */
  long ensureStub(String clusterExternalId, String kind, String name, String uid, String namespace);

  /** Creates an OWNS relationship (parent owns child); skipped if it already exists. */
  void linkOwner(long parentId, long childId);

  /**
   * Inserts or updates one container of a Pod; overwrites only when {@code observedAt} is not
   * earlier than the stored value.
   */
  void upsertContainer(long podId, PodContainerSnapshot container, Instant observedAt);

  /**
   * Marks as deleted the uids of this kind in this namespace that were absent from a complete list
   * that succeeded at {@code at} (only sets deleted_at, data is not deleted). Affects only rows
   * with {@code observed_at <= at} that are not yet marked, so data newer than this list is never
   * marked by mistake.
   *
   * @param liveUids the uids that exist right now (may be empty: the namespace has no resource of
   *     this kind any more)
   * @return the number of marked rows
   */
  int markMissingDeleted(
      String clusterExternalId,
      String namespace,
      String kind,
      Collection<String> liveUids,
      Instant at);
}
