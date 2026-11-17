package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.deployment.ArgoOperationSnapshot;
import io.cmdb.collector.inventory.domain.deployment.DeploymentStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaDeploymentStore implements DeploymentStore {

  private final SpringDataDeploymentRepository deployments;

  JpaDeploymentStore(SpringDataDeploymentRepository deployments) {
    this.deployments = deployments;
  }

  @Override
  @Transactional
  public void recordArgoOperation(long applicationId, ArgoOperationSnapshot o) {
    deployments.upsert(
        o.operationKey(),
        applicationId,
        o.gitRevision(),
        o.syncStatus(),
        o.healthStatus(),
        o.status(),
        o.statusMessage(),
        o.startedAt(),
        o.finishedAt(),
        o.metadata() == null ? null : o.metadata().toString());
    deployments.supersedeOlderRunning(applicationId);
  }
}
