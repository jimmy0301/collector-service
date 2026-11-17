package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationSnapshot;
import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.argo.ArgoResourceNode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Repository
class JpaArgoApplicationStore implements ArgoApplicationStore {

  private final SpringDataArgoApplicationRepository applications;

  JpaArgoApplicationStore(SpringDataArgoApplicationRepository applications) {
    this.applications = applications;
  }

  @Override
  @Transactional
  public long upsert(ArgoApplicationSnapshot a) {
    Long id =
        applications.upsertIfNewer(
            a.site(),
            a.uid(),
            a.name(),
            a.project(),
            a.destinationServer(),
            a.destinationName(),
            a.destinationNamespace(),
            json(a.sources()),
            a.syncStatus(),
            a.healthStatus(),
            a.autoSync(),
            json(a.syncRevisions()),
            a.observedAt());
    // When the data is older (condition fails) there is no RETURNING row, so look up the id
    return id != null ? id : findId(a.uid());
  }

  @Override
  @Transactional
  public long ensureStub(String site, String uid, String name) {
    applications.insertStubIfAbsent(site, uid, name);
    return findId(uid);
  }

  @Override
  @Transactional
  public int linkResources(long applicationId, List<ArgoResourceNode> nodes) {
    if (nodes.isEmpty()) {
      return 0;
    }
    var array = JsonNodeFactory.instance.arrayNode();
    for (ArgoResourceNode n : nodes) {
      var node = array.addObject();
      node.put("uid", n.uid());
      node.put("kind", n.kind());
      node.put("namespace", n.namespace());
      node.put("name", n.name());
      node.put("health", n.healthStatus());
    }
    String nodesJson = array.toString();
    return applications.upsertManagedResources(applicationId, nodesJson, Instant.EPOCH)
        + applications.linkResourcesByUid(applicationId, nodesJson);
  }

  @Override
  @Transactional
  public int linkUnmappedClusters(String site) {
    return applications.linkUnmappedClusters(site);
  }

  private long findId(String uid) {
    Long id = applications.findId(uid);
    if (id == null) {
      throw new IllegalStateException("argo_applications not found: " + uid);
    }
    return id;
  }

  private static String json(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.toString();
  }
}
