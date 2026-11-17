package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.k8s.K8sResourceState;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceState.ContainerImage;
import io.cmdb.collector.support.TestPayloads;
import java.util.List;
import org.junit.jupiter.api.Test;

class K8sResourceStatesTest {

  final TestPayloads payloads = new TestPayloads("t");

  @Test
  void podStateHasPhaseRestartSumAndDigestsSortedByName() {
    K8sResourceState s = K8sResourceStates.of("Pod", json(payloads.pod("12", "Running", null)));

    assertThat(s.phase()).isEqualTo("Running");
    assertThat(s.restartCount()).isEqualTo(5); // 2 + 3, init container excluded
    assertThat(s.replicas()).isNull();
    assertThat(s.readyReplicas()).isNull();
    assertThat(s.terminating()).isFalse();
    assertThat(s.images())
        .containsExactly(
            new ContainerImage("app", "acr.example.io/team/shop:1.2", "sha256:aaat"),
            new ContainerImage("proxy", "acr.example.io/team/proxy:3", "sha256:bbbt"));
  }

  @Test
  void aPodWithADeletionTimestampIsTerminating() {
    var s = K8sResourceStates.of("Pod", json(payloads.pod("1", "Running", "2026-11-14T00:00:00Z")));

    assertThat(s.terminating()).isTrue();
  }

  @Test
  void deploymentStateHasReplicasAndTheSpecImagesWithoutDigest() {
    var s =
        K8sResourceStates.of(
            "Deployment",
            json(
                """
                {"metadata":{"uid":"d"},
                 "spec":{"replicas":3,"template":{"spec":{"containers":[
                   {"name":"web","image":"acr.io/shop:2"},{"name":"app","image":"acr.io/app:1"}]}}},
                 "status":{"readyReplicas":2}}
                """));

    assertThat(s.phase()).isNull();
    assertThat(s.restartCount()).isZero();
    assertThat(s.replicas()).isEqualTo(3);
    assertThat(s.readyReplicas()).isEqualTo(2);
    assertThat(s.images())
        .containsExactly(
            new ContainerImage("app", "acr.io/app:1", null),
            new ContainerImage("web", "acr.io/shop:2", null));
  }

  @Test
  void readyReplicasDefaultsToZeroWhenKubernetesOmitsItAndIsNullWithoutAStatus() {
    var withStatus =
        K8sResourceStates.of("ReplicaSet", json("{\"spec\":{\"replicas\":2},\"status\":{}}"));
    var withoutStatus = K8sResourceStates.of("ReplicaSet", json("{\"spec\":{\"replicas\":2}}"));

    assertThat(withStatus.readyReplicas()).isZero();
    assertThat(withoutStatus.readyReplicas()).isNull();
  }

  @Test
  void aMissingReplicasFieldIsNullAndNoContainersGivesAnEmptyList() {
    var s = K8sResourceStates.of("Deployment", json("{\"spec\":{}}"));

    assertThat(s.replicas()).isNull();
    assertThat(s.images()).isEmpty();
  }

  @Test
  void onlyKeyFieldsMatterSoUnrelatedChangesGiveAnEqualState() {
    // resourceVersion and labels change all the time and must not start a history row
    var a = K8sResourceStates.of("Pod", json(payloads.pod("12", "Running", null)));
    var b = K8sResourceStates.of("Pod", json(payloads.pod("99", "Running", null)));

    assertThat(a).isEqualTo(b);
    assertThat(List.of(a, b)).hasSize(2);
  }
}
