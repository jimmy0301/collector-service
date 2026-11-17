package io.cmdb.collector.inventory.domain.k8s;

/**
 * Write side of k8s_events (see {@link SiteStore}). Events are kept forever; nothing here deletes
 * them.
 */
public interface K8sEventStore {

  /**
   * Inserts or updates the event. It overwrites only when {@code observedAt} is not earlier than
   * the stored value. The link to k8s_resources (and its service) is resolved from the involved uid
   * when the resource is already known, and is never cleared once set.
   */
  void upsert(K8sEventSnapshot event);
}
