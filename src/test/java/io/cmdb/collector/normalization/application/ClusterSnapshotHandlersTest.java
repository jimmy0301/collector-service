package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterSnapshotHandlersTest {

  final RecordingStores.Sites sites = new RecordingStores.Sites();
  final RecordingStores.Clusters clusters = new RecordingStores.Clusters();
  final RecordingStores.ArgoApplications applications = new RecordingStores.ArgoApplications();

  @Test
  void k8sSnapshotEnsuresTheSiteAndUpsertsEachCluster() {
    var handler = new K8sClusterSnapshotHandler(sites, clusters, applications);

    handler.handle(
        new RawEvent(
            "K8S",
            "CLUSTER_SNAPSHOT",
            "c1",
            "k",
            json(
                "{\"site\":\"A\",\"items\":[{\"id\":\"c-1\",\"name\":\"plant-a\",\"server\":\"https://k:6443\"},"
                    + "{\"id\":\"c-2\",\"name\":\"plant-b\"}]}")));

    assertThat(sites.ensured).containsExactly("A");
    assertThat(applications.clusterLinks).containsExactly("A"); // Applications seen earlier
    assertThat(clusters.upserts)
        .containsExactly(
            new ClusterSnapshot("A", "c-1", "plant-a", "https://k:6443"),
            new ClusterSnapshot("A", "c-2", "plant-b", null));
  }

  @Test
  void argoSnapshotSetsTheArgoNameForEachClusterWithServerAndName() {
    var handler = new ArgoClusterSnapshotHandler(clusters, applications);

    handler.handle(
        new RawEvent(
            "ARGO",
            "CLUSTER_SNAPSHOT",
            "c2",
            "k",
            json(
                "{\"site\":\"A\",\"resource\":{\"items\":["
                    + "{\"name\":\"argo-b\",\"server\":\"HTTPS://K.B:6443/\"},"
                    + "{\"name\":\"no-server\",\"server\":\"\"},"
                    + "{\"name\":\"\",\"server\":\"https://x\"}]}}")));

    assertThat(clusters.argoNames).containsExactly(List.of("A", "HTTPS://K.B:6443/", "argo-b"));
    assertThat(applications.clusterLinks).containsExactly("A");
  }

  @Test
  void anArgoClusterThatMatchesNothingIsNotAFailure() {
    clusters.matches = 0;
    var handler = new ArgoClusterSnapshotHandler(clusters, applications);

    handler.handle(
        new RawEvent(
            "ARGO",
            "CLUSTER_SNAPSHOT",
            "c3",
            "k",
            json(
                "{\"site\":\"A\",\"resource\":{\"items\":[{\"name\":\"in-cluster\",\"server\":\"https://kubernetes.default.svc\"}]}}")));

    assertThat(clusters.argoNames).hasSize(1); // tried, no exception
  }

  @Test
  void supportsOnlyTheirOwnProviderAndType() {
    assertThat(
            new K8sClusterSnapshotHandler(sites, clusters, applications)
                .supports("K8S", "CLUSTER_SNAPSHOT"))
        .isTrue();
    assertThat(
            new K8sClusterSnapshotHandler(sites, clusters, applications)
                .supports("ARGO", "CLUSTER_SNAPSHOT"))
        .isFalse();
    assertThat(
            new ArgoClusterSnapshotHandler(clusters, applications)
                .supports("ARGO", "CLUSTER_SNAPSHOT"))
        .isTrue();
    assertThat(
            new ArgoClusterSnapshotHandler(clusters, applications)
                .supports("K8S", "CLUSTER_SNAPSHOT"))
        .isFalse();
  }
}
