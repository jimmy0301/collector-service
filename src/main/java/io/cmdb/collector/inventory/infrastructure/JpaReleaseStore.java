package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.release.ReleaseDeploymentSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Repository
class JpaReleaseStore implements ReleaseStore {

  private final SpringDataReleaseRepository releases;

  JpaReleaseStore(SpringDataReleaseRepository releases) {
    this.releases = releases;
  }

  @Override
  @Transactional
  public long upsert(ReleaseSnapshot r) {
    Long id =
        releases.upsertIfNewer(
            r.project(),
            r.externalReleaseId(),
            r.definitionId(),
            r.definitionName(),
            r.name(),
            r.status(),
            r.reason(),
            r.artifactAlias(),
            r.artifactBuildRef(),
            r.sourceBranch(),
            json(r.artifacts()),
            r.createdBy(),
            r.createdAt(),
            r.modifiedAt());
    // When the data is older (condition fails) there is no RETURNING row, so look up the id
    return id != null ? id : findId(r.project(), r.externalReleaseId());
  }

  @Override
  @Transactional
  public long ensureStub(
      String project,
      String externalReleaseId,
      String definitionId,
      String definitionName,
      String name) {
    releases.insertStubIfAbsent(project, externalReleaseId, definitionId, definitionName, name);
    return findId(project, externalReleaseId);
  }

  @Override
  @Transactional
  public void upsertDeployment(ReleaseDeploymentSnapshot d) {
    releases.upsertDeploymentIfNewer(
        d.releaseId(),
        d.externalReleaseEnvId(),
        d.definitionEnvironmentId(),
        d.environmentName(),
        d.attempt(),
        d.externalDeploymentId(),
        d.deploymentStatus(),
        d.operationStatus(),
        d.reason(),
        d.requestedBy(),
        d.queuedAt(),
        d.startedAt(),
        d.completedAt(),
        d.lastModifiedAt());
  }

  private long findId(String project, String externalReleaseId) {
    Long id = releases.findId(project, externalReleaseId);
    if (id == null) {
      throw new IllegalStateException("release not found: " + project + "/" + externalReleaseId);
    }
    return id;
  }

  private static String json(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.toString();
  }
}
