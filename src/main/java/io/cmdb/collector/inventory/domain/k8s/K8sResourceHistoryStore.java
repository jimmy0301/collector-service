package io.cmdb.collector.inventory.domain.k8s;

import java.time.Instant;

/**
 * Keeps the history of a resource's key state (k8s_resource_history), forever. Implementations must
 * be idempotent and tolerate out-of-order events.
 */
public interface K8sResourceHistoryStore {

  /**
   * Records the state seen at {@code observedAt}:
   *
   * <ul>
   *   <li>no history yet → starts the first row;
   *   <li>same as the current row → nothing;
   *   <li>different and newer than the current row → closes the current row at {@code observedAt}
   *       and starts a new one;
   *   <li>not newer than the current row (or inside a closed range) → ignored, history is never
   *       rewritten.
   * </ul>
   */
  void record(long resourceId, K8sResourceState state, Instant observedAt);
}
