package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.inventory.domain.k8s.PodContainerSnapshot;
import java.time.Instant;
import java.util.Collection;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Repository
class JpaK8sResourceStore implements K8sResourceStore {

  private final SpringDataK8sResourceRepository resources;
  private final SpringDataK8sPodContainerRepository containers;
  private final SpringDataK8sResourceRelationshipRepository relationships;
  private final SpringDataK8sResourceHistoryRepository history;

  JpaK8sResourceStore(
      SpringDataK8sResourceRepository resources,
      SpringDataK8sPodContainerRepository containers,
      SpringDataK8sResourceRelationshipRepository relationships,
      SpringDataK8sResourceHistoryRepository history) {
    this.resources = resources;
    this.containers = containers;
    this.relationships = relationships;
    this.history = history;
  }

  @Override
  @Transactional
  public long upsert(K8sResourceSnapshot r) {
    Long id =
        resources.upsertIfNewer(
            r.clusterExternalId(),
            r.resourceUid(),
            r.namespace(),
            r.kind(),
            r.name(),
            r.apiVersion(),
            r.restartCount(),
            r.phase(),
            json(r.labels()),
            json(r.annotations()),
            json(r.spec()),
            json(r.status()),
            r.observedAt(),
            r.deletedAt());
    // When the data is older (condition fails) there is no RETURNING row, so look up the id
    return id != null ? id : findId(r.clusterExternalId(), r.resourceUid());
  }

  @Override
  @Transactional
  public long ensureStub(
      String clusterExternalId, String kind, String name, String uid, String namespace) {
    resources.insertStubIfAbsent(clusterExternalId, uid, namespace, kind, name, Instant.EPOCH);
    return findId(clusterExternalId, uid);
  }

  @Override
  @Transactional
  public void linkOwner(long parentId, long childId) {
    relationships.insertIfAbsent(parentId, childId, "OWNS");
  }

  @Override
  @Transactional
  public void upsertContainer(long podId, PodContainerSnapshot c, Instant observedAt) {
    containers.upsertIfNewer(
        podId,
        c.containerName(),
        c.init(),
        c.image(),
        c.imageId(),
        c.imageDigest(),
        c.ready(),
        c.restartCount(),
        c.state(),
        c.stateReason(),
        c.lastTerminatedReason(),
        c.lastExitCode(),
        c.lastTerminatedAt(),
        observedAt);
  }

  @Override
  @Transactional
  public int markMissingDeleted(
      String clusterExternalId,
      String namespace,
      String kind,
      Collection<String> liveUids,
      Instant at) {
    var uids = JsonNodeFactory.instance.arrayNode();
    liveUids.forEach(uids::add);
    int marked =
        resources.markMissingDeleted(clusterExternalId, namespace, kind, uids.toString(), at);
    if (marked > 0) {
      // The state history of a resource that no longer exists ends when it was marked deleted
      history.closeDeleted(clusterExternalId, namespace, kind, at);
    }
    return marked;
  }

  private long findId(String clusterExternalId, String uid) {
    Long id = resources.findId(clusterExternalId, uid);
    if (id == null) {
      throw new IllegalStateException(
          "k8s_resources not found (cluster missing?): " + clusterExternalId + "/" + uid);
    }
    return id;
  }

  /** JSONB parameters are passed as text and the SQL uses CAST(:x AS jsonb). */
  private static String json(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.toString();
  }
}
