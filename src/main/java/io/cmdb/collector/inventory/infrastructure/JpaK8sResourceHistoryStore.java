package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.k8s.K8sResourceHistoryStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceState;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

@Repository
class JpaK8sResourceHistoryStore implements K8sResourceHistoryStore {

  private final SpringDataK8sResourceHistoryRepository history;

  JpaK8sResourceHistoryStore(SpringDataK8sResourceHistoryRepository history) {
    this.history = history;
  }

  @Override
  @Transactional
  public void record(long resourceId, K8sResourceState s, Instant observedAt) {
    String images = images(s);
    history.closeIfChanged(
        resourceId,
        s.phase(),
        s.restartCount(),
        s.replicas(),
        s.readyReplicas(),
        images,
        s.terminating(),
        observedAt);
    history.startIfNoCurrent(
        resourceId,
        s.phase(),
        s.restartCount(),
        s.replicas(),
        s.readyReplicas(),
        images,
        s.terminating(),
        observedAt);
  }

  /** JSONB compares by content, so the list order (sorted by the caller) is the only variable. */
  private static String images(K8sResourceState s) {
    var array = JsonNodeFactory.instance.arrayNode();
    for (var image : s.images()) {
      var node = array.addObject();
      node.put("name", image.name());
      node.put("image", image.image());
      node.put("digest", image.digest());
    }
    return array.toString();
  }
}
