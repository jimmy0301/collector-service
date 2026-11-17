package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationSnapshot;
import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.argo.ArgoResourceNode;
import io.cmdb.collector.inventory.domain.artifact.ArtifactStore;
import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.deployment.ArgoOperationSnapshot;
import io.cmdb.collector.inventory.domain.deployment.DeploymentStore;
import io.cmdb.collector.inventory.domain.k8s.K8sEventSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sEventStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceHistoryStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceState;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.inventory.domain.k8s.PodContainerSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseDeploymentSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseSnapshot;
import io.cmdb.collector.inventory.domain.release.ReleaseStore;
import io.cmdb.collector.inventory.domain.site.SiteSnapshot;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stores that only record what a handler asked them to write, so handler tests are plain unit tests
 * (no Spring, no database). What the SQL really does is covered by the {@code Jpa*StoreIT} tests.
 */
final class RecordingStores {

  private RecordingStores() {}

  static class Sites implements SiteStore {
    final List<SiteSnapshot> upserts = new ArrayList<>();
    final List<String> ensured = new ArrayList<>();

    @Override
    public void upsert(SiteSnapshot site) {
      upserts.add(site);
    }

    @Override
    public void ensureExists(String code) {
      ensured.add(code);
    }
  }

  static class Clusters implements ClusterStore {
    final List<ClusterSnapshot> upserts = new ArrayList<>();
    final List<ClusterSnapshot> ensured = new ArrayList<>();
    final List<List<String>> argoNames = new ArrayList<>(); // [site, server, name]
    int matches = 1;

    @Override
    public void upsert(ClusterSnapshot cluster) {
      upserts.add(cluster);
    }

    @Override
    public void ensureExists(ClusterSnapshot cluster) {
      ensured.add(cluster);
    }

    @Override
    public int setArgoClusterName(String siteCode, String argoServerUrl, String argoClusterName) {
      argoNames.add(List.of(siteCode, argoServerUrl, argoClusterName));
      return matches;
    }
  }

  static class Artifacts implements ArtifactStore {
    final List<List<String>> ensured = new ArrayList<>(); // [registry, repository, tag, digest]

    @Override
    public void ensureExists(String registry, String repository, String tag, String digest) {
      ensured.add(List.of(registry, repository, tag == null ? "" : tag, digest));
    }
  }

  static class Events implements K8sEventStore {
    final List<K8sEventSnapshot> upserts = new ArrayList<>();

    @Override
    public void upsert(K8sEventSnapshot event) {
      upserts.add(event);
    }
  }

  record Recorded(long resourceId, K8sResourceState state, Instant observedAt) {}

  static class History implements K8sResourceHistoryStore {
    final List<Recorded> recorded = new ArrayList<>();

    @Override
    public void record(long resourceId, K8sResourceState state, Instant observedAt) {
      recorded.add(new Recorded(resourceId, state, observedAt));
    }
  }

  record Stub(String kind, String name, String uid, String namespace) {}

  record Link(long parentId, long childId) {}

  record Container(long podId, PodContainerSnapshot container, Instant observedAt) {}

  record Deletion(
      String clusterId, String namespace, String kind, List<String> liveUids, Instant at) {}

  static class Resources implements K8sResourceStore {
    final List<K8sResourceSnapshot> upserts = new ArrayList<>();
    final List<Stub> stubs = new ArrayList<>();
    final List<Link> links = new ArrayList<>();
    final List<Container> containers = new ArrayList<>();
    final List<Deletion> deletions = new ArrayList<>();
    private long next = 100;

    @Override
    public long upsert(K8sResourceSnapshot resource) {
      upserts.add(resource);
      return ++next;
    }

    @Override
    public long ensureStub(
        String clusterExternalId, String kind, String name, String uid, String namespace) {
      stubs.add(new Stub(kind, name, uid, namespace));
      return ++next;
    }

    @Override
    public void linkOwner(long parentId, long childId) {
      links.add(new Link(parentId, childId));
    }

    @Override
    public void upsertContainer(long podId, PodContainerSnapshot container, Instant observedAt) {
      containers.add(new Container(podId, container, observedAt));
    }

    @Override
    public int markMissingDeleted(
        String clusterExternalId,
        String namespace,
        String kind,
        Collection<String> liveUids,
        Instant at) {
      deletions.add(
          new Deletion(clusterExternalId, namespace, kind, new ArrayList<>(liveUids), at));
      return 0;
    }
  }

  record ReleaseStub(
      String project, String releaseId, String definitionId, String definitionName, String name) {}

  static class Releases implements ReleaseStore {
    final List<ReleaseSnapshot> upserts = new ArrayList<>();
    final List<ReleaseStub> stubs = new ArrayList<>();
    final List<ReleaseDeploymentSnapshot> deployments = new ArrayList<>();

    @Override
    public long upsert(ReleaseSnapshot release) {
      upserts.add(release);
      return 7;
    }

    @Override
    public long ensureStub(
        String project, String releaseId, String definitionId, String definitionName, String name) {
      stubs.add(new ReleaseStub(project, releaseId, definitionId, definitionName, name));
      return 7;
    }

    @Override
    public void upsertDeployment(ReleaseDeploymentSnapshot deployment) {
      deployments.add(deployment);
    }
  }

  record AppStub(String site, String uid, String name) {}

  record AppLink(long applicationId, List<ArgoResourceNode> nodes) {}

  static class ArgoApplications implements ArgoApplicationStore {
    final List<ArgoApplicationSnapshot> upserts = new ArrayList<>();
    final List<AppStub> stubs = new ArrayList<>();
    final List<AppLink> links = new ArrayList<>();
    final List<String> clusterLinks = new ArrayList<>();

    @Override
    public long upsert(ArgoApplicationSnapshot application) {
      upserts.add(application);
      return 5;
    }

    @Override
    public long ensureStub(String site, String uid, String name) {
      stubs.add(new AppStub(site, uid, name));
      return 5;
    }

    @Override
    public int linkResources(long applicationId, List<ArgoResourceNode> nodes) {
      links.add(new AppLink(applicationId, List.copyOf(nodes)));
      return nodes.size();
    }

    @Override
    public int linkUnmappedClusters(String site) {
      clusterLinks.add(site);
      return 0;
    }
  }

  record RecordedOperation(long applicationId, ArgoOperationSnapshot operation) {}

  static class Deployments implements DeploymentStore {
    final List<RecordedOperation> recorded = new ArrayList<>();

    @Override
    public void recordArgoOperation(long applicationId, ArgoOperationSnapshot operation) {
      recorded.add(new RecordedOperation(applicationId, operation));
    }
  }
}
