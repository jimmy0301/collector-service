package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.PodContainerSnapshot;
import io.cmdb.collector.normalization.application.RecordingStores.Link;
import io.cmdb.collector.normalization.application.RecordingStores.Stub;
import io.cmdb.collector.rawevent.domain.RawEvent;
import io.cmdb.collector.support.TestPayloads;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class K8sResourceHandlerTest {

  static final String T = "2026-11-14T00:00:01Z";

  final TestPayloads payloads = new TestPayloads("t");
  final RecordingStores.Sites sites = new RecordingStores.Sites();
  final RecordingStores.Clusters clusters = new RecordingStores.Clusters();
  final RecordingStores.Resources resources = new RecordingStores.Resources();
  final RecordingStores.Artifacts artifacts = new RecordingStores.Artifacts();
  final RecordingStores.History history = new RecordingStores.History();
  final K8sResourceHandler handler =
      new K8sResourceHandler(sites, clusters, resources, artifacts, history);

  void handle(String type, String object) {
    handler.handle(new RawEvent("K8S", type, "e", "k", json(payloads.k8s(T, object))));
  }

  @Test
  void deploymentKeepsSpecAndStatusAndTheServiceLabels() {
    handle("Deployment", payloads.deployment("10"));

    K8sResourceSnapshot r = resources.upserts.get(0);
    assertThat(r.kind()).isEqualTo("Deployment");
    assertThat(r.name()).isEqualTo("shop");
    assertThat(r.namespace()).isEqualTo("shop");
    assertThat(r.apiVersion()).isEqualTo("apps/v1");
    assertThat(r.spec().path("replicas").asInt()).isEqualTo(2);
    assertThat(r.status()).isNotNull();
    assertThat(r.labels().path("cmdb.io/service-id").asString()).isEqualTo("SVC-t");
    assertThat(r.phase()).isNull();
    assertThat(r.restartCount()).isZero();
    assertThat(r.observedAt()).isEqualTo(Instant.parse(T));
    assertThat(r.deletedAt()).isNull();
  }

  @Test
  void ensuresTheSiteAndClusterFirst() {
    handle("Deployment", payloads.deployment("10"));

    assertThat(sites.ensured).containsExactly(payloads.site);
    assertThat(clusters.ensured)
        .containsExactly(new ClusterSnapshot(payloads.site, payloads.clusterId, "plant-a", null));
  }

  @Test
  void podStoresPhaseAndTheRestartSumWithoutInitContainersAndNeitherSpecNorStatus() {
    handle("Pod", payloads.pod("12", "Running", null));

    K8sResourceSnapshot r = resources.upserts.get(0);
    assertThat(r.phase()).isEqualTo("Running");
    assertThat(r.restartCount()).isEqualTo(5); // 2 + 3, the init container is excluded
    assertThat(r.spec()).isNull();
    assertThat(r.status()).isNull();
    assertThat(r.apiVersion()).isEqualTo("v1");
  }

  @Test
  void deletionTimestampBecomesDeletedAt() {
    handle("Pod", payloads.pod("30", "Running", "2026-11-14T00:00:00Z"));

    assertThat(resources.upserts.get(0).deletedAt())
        .isEqualTo(Instant.parse("2026-11-14T00:00:00Z"));
  }

  @Test
  void everyObservationIsRecordedInTheHistoryWithTheKeyStateOfThatKind() {
    handle("Pod", payloads.pod("12", "Running", null));
    handle("Deployment", payloads.deployment("10"));

    assertThat(history.recorded).hasSize(2);
    var pod = history.recorded.get(0);
    assertThat(pod.resourceId()).isEqualTo(101); // the id the store returned for the Pod
    assertThat(pod.observedAt()).isEqualTo(Instant.parse(T));
    assertThat(pod.state().phase()).isEqualTo("Running");
    assertThat(pod.state().restartCount()).isEqualTo(5);
    assertThat(history.recorded.get(1).state().replicas()).isEqualTo(2);
  }

  @Test
  void ownerReferencesBecomeStubsLinkedToTheChild() {
    handle("Pod", payloads.pod("12", "Running", null));

    // the owner is created as a stub (it may be processed later) and linked parent → child
    assertThat(resources.stubs)
        .containsExactly(new Stub("ReplicaSet", "shop-rs", payloads.rsUid, "shop"));
    long childId = 101; // the Pod is upserted first
    assertThat(resources.links).containsExactly(new Link(102, childId));
  }

  @Test
  void ownersOfOtherKindsAreIgnored() {
    handle(
        "Pod",
        """
        {"metadata":{"uid":"p","name":"n","namespace":"shop",
           "ownerReferences":[{"kind":"Node","name":"n1","uid":"x"}]},"kind":"Pod"}
        """);

    assertThat(resources.stubs).isEmpty();
    assertThat(resources.links).isEmpty();
  }

  @Test
  void everyContainerIncludingInitOnesIsStoredWithItsStateAndDigest() {
    handle("Pod", payloads.pod("12", "Running", null));

    List<PodContainerSnapshot> cs = resources.containers.stream().map(c -> c.container()).toList();
    assertThat(cs)
        .extracting(PodContainerSnapshot::containerName)
        .containsExactly("app", "proxy", "init");
    PodContainerSnapshot app = cs.get(0);
    assertThat(app.init()).isFalse();
    assertThat(app.imageDigest()).isEqualTo("sha256:aaat");
    assertThat(app.state()).isEqualTo("running");
    assertThat(app.ready()).isTrue();
    assertThat(app.restartCount()).isEqualTo(2);
    assertThat(app.lastTerminatedReason()).isEqualTo("OOMKilled");
    assertThat(app.lastExitCode()).isEqualTo(137);
    assertThat(app.lastTerminatedAt()).isEqualTo(Instant.parse("2026-11-13T23:00:00Z"));
    PodContainerSnapshot proxy = cs.get(1);
    assertThat(proxy.state()).isEqualTo("waiting");
    assertThat(proxy.stateReason()).isEqualTo("CrashLoopBackOff");
    assertThat(proxy.imageDigest()).isEqualTo("sha256:bbbt"); // docker-pullable:// prefix removed
    assertThat(cs.get(2).init()).isTrue();
    assertThat(resources.containers).allMatch(c -> c.observedAt().equals(Instant.parse(T)));
  }

  @Test
  void anArtifactIsEnsuredForEachDigest() {
    handle("Pod", payloads.pod("12", "Running", null));

    assertThat(artifacts.ensured)
        .containsExactly(
            List.of("acr.example.io", "team/shop", "1.2", "sha256:aaat"),
            List.of("acr.example.io", "team/proxy", "3", "sha256:bbbt"),
            List.of("acr.example.io", "team/init", "1", "sha256:ccct"));
  }

  @Test
  void aContainerWithoutADigestIsStoredButGetsNoArtifact() {
    handle(
        "Pod",
        """
        {"metadata":{"uid":"p","name":"n","namespace":"shop"},"kind":"Pod",
         "status":{"phase":"Pending","containerStatuses":[{"name":"app","image":"nginx:1","imageID":""}]}}
        """);

    assertThat(resources.containers).hasSize(1);
    assertThat(resources.containers.get(0).container().imageDigest()).isNull();
    assertThat(artifacts.ensured).isEmpty();
  }

  @Test
  void aMissingRequiredFieldFailsTheEventBeforeAnythingIsWritten() {
    assertThatThrownBy(
            () ->
                handler.handle(
                    new RawEvent(
                        "K8S",
                        "Pod",
                        "e",
                        "k",
                        json(payloads.k8s("not-a-time", payloads.pod("1", "Running", null))))))
        .isInstanceOf(java.time.format.DateTimeParseException.class);
    assertThat(resources.upserts).isEmpty();

    assertThatThrownBy(() -> handle("Pod", "{\"metadata\":{\"name\":\"n\"},\"kind\":\"Pod\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("uid");
  }

  @Test
  void supportsTheThreeWorkloadKinds() {
    assertThat(handler.supports("K8S", "Deployment")).isTrue();
    assertThat(handler.supports("K8S", "ReplicaSet")).isTrue();
    assertThat(handler.supports("K8S", "Pod")).isTrue();
    assertThat(handler.supports("K8S", "Event")).isFalse();
    assertThat(handler.supports("ARGO", "Pod")).isFalse();
  }
}
