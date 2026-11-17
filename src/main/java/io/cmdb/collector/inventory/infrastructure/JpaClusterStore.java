package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaClusterStore implements ClusterStore {

  private final SpringDataClusterRepository clusters;

  JpaClusterStore(SpringDataClusterRepository clusters) {
    this.clusters = clusters;
  }

  @Override
  @Transactional
  public void upsert(ClusterSnapshot c) {
    clusters.lockCluster(lockKey(c));
    clusters.upsert(c.defaultCode(), c.siteCode(), c.externalId(), c.serverUrl(), c.name());
  }

  @Override
  @Transactional
  public void ensureExists(ClusterSnapshot c) {
    clusters.lockCluster(lockKey(c));
    clusters.insertIfAbsent(c.defaultCode(), c.siteCode(), c.externalId(), c.serverUrl(), c.name());
  }

  private static String lockKey(ClusterSnapshot c) {
    return "cluster:" + c.externalId();
  }

  @Override
  @Transactional
  public int setArgoClusterName(String siteCode, String argoServerUrl, String argoClusterName) {
    return clusters.setArgoClusterName(siteCode, argoServerUrl, argoClusterName);
  }
}
