package io.cmdb.collector.normalization.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationSnapshot;
import io.cmdb.collector.inventory.domain.argo.ArgoResourceNode;
import io.cmdb.collector.normalization.application.RecordingStores.AppLink;
import io.cmdb.collector.normalization.application.RecordingStores.AppStub;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgoHandlersTest {

  final RecordingStores.ArgoApplications applications = new RecordingStores.ArgoApplications();
  final RecordingStores.Deployments deployments = new RecordingStores.Deployments();
  final ArgoApplicationHandler applicationHandler =
      new ArgoApplicationHandler(applications, deployments);
  final ArgoResourceTreeHandler treeHandler = new ArgoResourceTreeHandler(applications);

  static RawEvent app(String resource) {
    return new RawEvent(
        "ARGO",
        "APPLICATION_SNAPSHOT",
        "e",
        "k",
        json(
            "{\"site\":\"PLANT-A\",\"fetchedAt\":\"2026-11-14T03:00:00Z\",\"resource\":"
                + resource
                + "}"));
  }

  static RawEvent tree(String resource) {
    return new RawEvent(
        "ARGO",
        "RESOURCE_TREE_SNAPSHOT",
        "e",
        "k",
        json(
            "{\"site\":\"PLANT-A\",\"fetchedAt\":\"2026-11-14T03:00:00Z\","
                + "\"application\":{\"uid\":\"app-1\",\"name\":\"shop\"},\"resource\":"
                + resource
                + "}"));
  }

  static final String APP =
      """
      {"metadata":{"uid":"app-1","name":"shop","namespace":"argocd"},
       "spec":{"project":"default",
         "destination":{"server":"https://k:6443","name":"plant-a","namespace":"shop"},
         "source":{"repoURL":"https://git/x","path":"overlays/prod","targetRevision":"main",
                   "helm":{"values":"password: hunter2"}},
         "syncPolicy":{"automated":{"prune":true}}},
       "status":{"sync":{"status":"Synced","revision":"abc123"},"health":{"status":"Healthy"}}}
      """;

  // ---------- Application ----------

  @Test
  void supportsOnlyTheirOwnKindsFromArgo() {
    assertThat(applicationHandler.supports("ARGO", "APPLICATION_SNAPSHOT")).isTrue();
    assertThat(applicationHandler.supports("ARGO", "RESOURCE_TREE_SNAPSHOT")).isFalse();
    assertThat(treeHandler.supports("ARGO", "RESOURCE_TREE_SNAPSHOT")).isTrue();
    assertThat(treeHandler.supports("K8S", "RESOURCE_TREE_SNAPSHOT")).isFalse();
  }

  @Test
  void anApplicationMapsEveryField() {
    applicationHandler.handle(app(APP));

    ArgoApplicationSnapshot a = applications.upserts.get(0);
    assertThat(a.site()).isEqualTo("PLANT-A");
    assertThat(a.uid()).isEqualTo("app-1");
    assertThat(a.name()).isEqualTo("shop");
    assertThat(a.project()).isEqualTo("default");
    assertThat(a.destinationServer()).isEqualTo("https://k:6443");
    assertThat(a.destinationName()).isEqualTo("plant-a");
    assertThat(a.destinationNamespace()).isEqualTo("shop");
    assertThat(a.syncStatus()).isEqualTo("Synced");
    assertThat(a.healthStatus()).isEqualTo("Healthy");
    assertThat(a.autoSync()).isTrue();
    assertThat(a.syncRevisions().toString()).isEqualTo("[\"abc123\"]");
    assertThat(a.observedAt()).isEqualTo(Instant.parse("2026-11-14T03:00:00Z"));
  }

  @Test
  void aSingleSourceBecomesAOneElementArrayWithoutHelmValues() {
    applicationHandler.handle(app(APP));

    var sources = applications.upserts.get(0).sources();
    assertThat(sources).hasSize(1);
    assertThat(sources.get(0).path("repoURL").asString()).isEqualTo("https://git/x");
    assertThat(sources.get(0).path("path").asString()).isEqualTo("overlays/prod");
    assertThat(sources.get(0).path("targetRevision").asString()).isEqualTo("main");
    assertThat(sources.toString()).doesNotContain("hunter2");
  }

  @Test
  void multipleSourcesAndRevisionsAreKeptAsArrays() {
    applicationHandler.handle(
        app(
            """
            {"metadata":{"uid":"app-2","name":"multi"},
             "spec":{"sources":[{"repoURL":"https://a","chart":"c","targetRevision":"1.0"},
                                {"repoURL":"https://b","ref":"values"}]},
             "status":{"sync":{"status":"OutOfSync","revisions":["r1","r2"]}}}
            """));

    var a = applications.upserts.get(0);
    assertThat(a.sources()).hasSize(2);
    assertThat(a.sources().get(0).path("chart").asString()).isEqualTo("c");
    assertThat(a.sources().get(1).path("ref").asString()).isEqualTo("values");
    assertThat(a.syncRevisions().toString()).isEqualTo("[\"r1\",\"r2\"]");
    assertThat(a.autoSync()).isFalse();
    assertThat(a.healthStatus()).isNull();
  }

  @Test
  void anEmptyAutomatedPolicyStillMeansAutomaticSyncAndNoPolicyMeansManual() {
    applicationHandler.handle(
        app(
            "{\"metadata\":{\"uid\":\"a\",\"name\":\"a\"},\"spec\":{\"syncPolicy\":{\"automated\":{}}}}"));
    applicationHandler.handle(
        app("{\"metadata\":{\"uid\":\"b\",\"name\":\"b\"},\"spec\":{\"syncPolicy\":{}}}"));

    assertThat(applications.upserts.get(0).autoSync()).isTrue();
    assertThat(applications.upserts.get(1).autoSync()).isFalse();
  }

  @Test
  void anApplicationWithoutUidFailsTheEvent() {
    assertThatThrownBy(() -> applicationHandler.handle(app("{\"metadata\":{\"name\":\"x\"}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("uid");
  }

  @Test
  void theLatestSyncOperationIsRecordedAsADeploymentOfTheApplication() {
    applicationHandler.handle(
        app(
            "{\"metadata\":{\"uid\":\"app-1\",\"name\":\"shop\"},"
                + "\"status\":{\"sync\":{\"status\":\"Synced\"},\"health\":{\"status\":\"Healthy\"},"
                + "\"operationState\":{\"phase\":\"Succeeded\",\"startedAt\":\"2026-11-14T01:00:00Z\","
                + "\"finishedAt\":\"2026-11-14T01:01:00Z\"}}}"));

    var recorded = deployments.recorded.get(0);
    assertThat(recorded.applicationId()).isEqualTo(5); // the id the store returned
    assertThat(recorded.operation().operationKey()).isEqualTo("app-1:2026-11-14T01:00:00Z");
    assertThat(recorded.operation().status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void anApplicationThatWasNeverSyncedRecordsNoDeployment() {
    applicationHandler.handle(app(APP));

    assertThat(deployments.recorded).isEmpty();
  }

  @Test
  void anUnknownOperationPhaseFailsTheEventInsteadOfBeingGuessed() {
    var event =
        app(
            "{\"metadata\":{\"uid\":\"a\",\"name\":\"a\"},\"status\":{\"operationState\":"
                + "{\"phase\":\"Paused\",\"startedAt\":\"2026-11-14T01:00:00Z\"}}}");

    assertThatThrownBy(() -> applicationHandler.handle(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Paused");
  }

  // ---------- resource-tree ----------

  @Test
  void aTreeEnsuresItsApplicationAndLinksOnlyTheManagedNodes() {
    treeHandler.handle(
        tree(
            """
            {"nodes":[{"uid":"dep-1","kind":"Deployment","namespace":"shop","name":"shop",
                       "health":{"status":"Healthy"}},
                      {"uid":"rs-1","kind":"ReplicaSet","namespace":"shop","name":"shop-rs"},
                      {"kind":"Service","name":"no-uid"}],
             "orphanedNodes":[{"uid":"orphan-1","kind":"Pod"}]}
            """));

    assertThat(applications.stubs).containsExactly(new AppStub("PLANT-A", "app-1", "shop"));
    assertThat(applications.links)
        .containsExactly(
            new AppLink(
                5,
                List.of(
                    new ArgoResourceNode("dep-1", "Deployment", "shop", "shop", "Healthy"),
                    new ArgoResourceNode("rs-1", "ReplicaSet", "shop", "shop-rs", null))));
  }

  @Test
  void aTreeWithoutApplicationInfoFailsTheEvent() {
    var event =
        new RawEvent(
            "ARGO",
            "RESOURCE_TREE_SNAPSHOT",
            "e",
            "k",
            json("{\"site\":\"PLANT-A\",\"resource\":{\"nodes\":[]}}"));

    assertThatThrownBy(() -> treeHandler.handle(event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("uid");
    assertThat(applications.links).isEmpty();
  }
}
