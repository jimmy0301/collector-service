package io.cmdb.collector.k8s.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.argo.ArgoScopeObserved;
import io.cmdb.collector.argo.Destination;
import io.cmdb.collector.k8s.domain.Cluster;
import io.cmdb.collector.k8s.domain.K8sHub;
import io.cmdb.collector.k8s.domain.ResourceKind;
import io.cmdb.collector.k8s.domain.Site;
import io.cmdb.collector.rawevent.application.RawEventRecorder;
import io.cmdb.collector.support.Fakes.InMemoryRuns;
import io.cmdb.collector.support.Fakes.InMemorySink;
import io.cmdb.collector.syncrun.application.SyncRunTracker;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class K8sCollectorTest {

  static class FakeHub implements K8sHub {
    final Map<ResourceKind, List<JsonNode>> items = new EnumMap<>(ResourceKind.class);
    final List<ResourceKind> failing = new ArrayList<>();
    final List<String> calls = new ArrayList<>();

    @Override
    public List<String> sites() {
      return List.of("PLANT-A");
    }

    @Override
    public List<Site> siteDetails() {
      return List.of(new Site("PLANT-A", "A 廠", null));
    }

    @Override
    public List<Cluster> clusters(String site) {
      return List.of(new Cluster("cid-1", "plant-a", "https://k8s.plant-a:6443"));
    }

    @Override
    public void list(
        String site,
        String clusterId,
        ResourceKind kind,
        String ns,
        String fieldSelector,
        Consumer<JsonNode> consumer) {
      calls.add("%s/%s/%s/%s".formatted(clusterId, ns, kind, fieldSelector));
      if (failing.contains(kind)) {
        throw new IllegalStateException("504 site unreachable");
      }
      items.getOrDefault(kind, List.of()).forEach(consumer);
    }
  }

  static final String POD =
      """
      {"metadata":{"uid":"pod-1","name":"shop-abc","namespace":"shop","resourceVersion":"100",
         "ownerReferences":[{"kind":"ReplicaSet","name":"shop-abc","uid":"rs-1","controller":true}],
         "managedFields":[{"x":1}]},
       "spec":{"containers":[{"name":"app","env":[{"name":"P","value":"hunter2"}]}]},
       "status":{"phase":"Running","podIP":"10.0.0.1",
         "containerStatuses":[{"name":"app","image":"acr/shop:1","imageID":"acr/shop@sha256:abc",
           "restartCount":3,"ready":true,"state":{"running":{}},
           "lastState":{"terminated":{"reason":"OOMKilled","exitCode":137}}}]}}
      """;

  static final String DEPLOYMENT =
      """
      {"metadata":{"uid":"dep-1","name":"shop","namespace":"shop","resourceVersion":"7"},
       "spec":{"replicas":2,"template":{"spec":{"containers":[
          {"name":"app","image":"acr/shop:1","env":[{"name":"DB_URL","value":"jdbc:secret"}]}]}}},
       "status":{"readyReplicas":2}}
      """;

  FakeHub hub = new FakeHub();
  InMemorySink sink = new InMemorySink();
  InMemoryRuns runs = new InMemoryRuns();
  ManagedNamespaces scope = new ManagedNamespaces();
  K8sCollector collector;

  @BeforeEach
  void setUp() {
    collector =
        new K8sCollector(
            hub,
            new RawEventRecorder(sink, event -> {}, new SimpleMeterRegistry(), 1000),
            new SyncRunTracker(runs, Clock.systemUTC()),
            scope,
            Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
    scope.on(
        new ArgoScopeObserved(
            "PLANT-A", List.of(new Destination("https://k8s.plant-a:6443/", null, "shop"))));
  }

  @Test
  void recordsWorkloadsWithClusterIdentityAndResourceVersionInEventId() {
    hub.items.put(ResourceKind.DEPLOYMENT, List.of(json(DEPLOYMENT)));
    hub.items.put(ResourceKind.POD, List.of(json(POD)));

    collector.collectWorkloads();

    var pod = sink.ofType("Pod").getFirst();
    assertThat(pod.provider()).isEqualTo("K8S");
    assertThat(pod.externalEventId()).isEqualTo("k8s:cid-1:pod-1:100");
    assertThat(pod.orderingKey()).isEqualTo("k8s:cid-1:pod-1");
    assertThat(pod.payload().at("/cluster/id").asString()).isEqualTo("cid-1");
    assertThat(pod.payload().at("/site").asString()).isEqualTo("PLANT-A");
    assertThat(pod.payload().at("/object/kind").asString()).isEqualTo("Pod");
    assertThat(runs.last().provider()).isEqualTo("K8S:PLANT-A:plant-a");
    assertThat(runs.last().status()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(hub.calls)
        .contains(
            "cid-1/shop/POD/null", "cid-1/shop/DEPLOYMENT/null", "cid-1/shop/REPLICASET/null");
  }

  @Test
  void podPayloadKeepsContainerStatusesButNotSpecOrOtherStatus() {
    hub.items.put(ResourceKind.POD, List.of(json(POD)));

    collector.collectWorkloads();

    JsonNode obj = sink.ofType("Pod").getFirst().payload().path("object");
    assertThat(obj.has("spec")).isFalse();
    assertThat(obj.at("/status").has("podIP")).isFalse();
    assertThat(obj.at("/status/containerStatuses/0/imageID").asString())
        .isEqualTo("acr/shop@sha256:abc");
    assertThat(obj.at("/status/containerStatuses/0/restartCount").asInt()).isEqualTo(3);
    assertThat(obj.at("/status/containerStatuses/0/lastState/terminated/reason").asString())
        .isEqualTo("OOMKilled");
    assertThat(obj.at("/metadata/ownerReferences/0/uid").asString()).isEqualTo("rs-1");
    assertThat(obj.at("/metadata").has("managedFields")).isFalse();
  }

  @Test
  void deploymentIsKeptWholeButEnvValuesAreRedacted() {
    hub.items.put(ResourceKind.DEPLOYMENT, List.of(json(DEPLOYMENT)));

    collector.collectWorkloads();

    JsonNode obj = sink.ofType("Deployment").getFirst().payload().path("object");
    assertThat(obj.at("/spec/replicas").asInt()).isEqualTo(2);
    assertThat(obj.at("/spec/template/spec/containers/0/env/0/name").asString())
        .isEqualTo("DB_URL");
    assertThat(obj.toString()).doesNotContain("jdbc:secret");
  }

  @Test
  void recordsTheSetOfLiveUidsPerNamespaceAndKindSoDeletionsCanBeDetected() {
    hub.items.put(ResourceKind.POD, List.of(json(POD)));

    collector.collectWorkloads();

    var list =
        sink.ofType("RESOURCE_LIST").stream()
            .filter(e -> "Pod".equals(e.payload().path("kind").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(list.orderingKey()).isEqualTo("k8s:cid-1:list:shop:Pod");
    assertThat(list.payload().path("namespace").asString()).isEqualTo("shop");
    assertThat(list.payload().path("uids").get(0).asString()).isEqualTo("pod-1");
    assertThat(list.payload().path("fetchedAt").asString()).isEqualTo("2026-11-10T00:00:00Z");
    // A kind with no resources is also recorded (empty array): it means "there are none right now"
    assertThat(
            sink.ofType("RESOURCE_LIST").stream()
                .filter(e -> "Deployment".equals(e.payload().path("kind").asString()))
                .findFirst()
                .orElseThrow()
                .payload()
                .path("uids"))
        .isEmpty();
    // Not repeated within the same hour when the content is unchanged
    collector.collectWorkloads();
    assertThat(sink.ofType("RESOURCE_LIST")).hasSize(3);
  }

  @Test
  void noLiveListIsRecordedForAKindWhoseListFailedOrForEvents() {
    hub.items.put(ResourceKind.POD, List.of(json(POD)));
    hub.failing.add(ResourceKind.REPLICASET);

    collector.collectWorkloads();
    collector.collectEvents();

    assertThat(sink.ofType("RESOURCE_LIST"))
        .extracting(e -> e.payload().path("kind").asString())
        .containsExactlyInAnyOrder("Pod", "Deployment"); // ReplicaSet failed, Event is not recorded
  }

  @Test
  void samePollTwiceDoesNotDuplicateButNewResourceVersionDoes() {
    hub.items.put(ResourceKind.POD, List.of(json(POD)));
    collector.collectWorkloads();
    collector.collectWorkloads();
    assertThat(sink.ofType("Pod")).hasSize(1);

    hub.items.put(ResourceKind.POD, List.of(json(POD.replace("\"100\"", "\"101\""))));
    collector.collectWorkloads();
    assertThat(sink.ofType("Pod")).hasSize(2);
  }

  @Test
  void eventsUseWarningFieldSelector() {
    hub.items.put(
        ResourceKind.EVENT,
        List.of(
            json(
                "{\"metadata\":{\"uid\":\"ev-1\",\"resourceVersion\":\"5\",\"namespace\":\"shop\"},\"type\":\"Warning\",\"reason\":\"BackOff\"}")));

    collector.collectEvents();

    assertThat(hub.calls).containsExactly("cid-1/shop/EVENT/type=Warning");
    assertThat(sink.ofType("Event").getFirst().externalEventId()).isEqualTo("k8s:cid-1:ev-1:5");
    assertThat(runs.last().provider()).isEqualTo("K8S_EVENT:PLANT-A:plant-a");
  }

  @Test
  void skipsClusterWithoutArgoScopeAndRecordsNoRun() {
    scope.on(new ArgoScopeObserved("PLANT-A", List.of()));

    var result = collector.collectWorkloads();

    assertThat(hub.calls).isEmpty();
    assertThat(runs.runs).isEmpty();
    assertThat(result.runs()).isZero(); // lets the trigger API say "nothing in scope"
    assertThat(result.status()).isEqualTo(SyncStatus.SUCCESS);
  }

  @Test
  void partialWhenSomeListsFailAndFailedWhenAllFail() {
    hub.items.put(ResourceKind.POD, List.of(json(POD)));
    hub.failing.add(ResourceKind.REPLICASET);
    var partial = collector.collectWorkloads();
    assertThat(partial.status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(runs.last().status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(runs.last().error()).contains("shop/ReplicaSet").contains("504");
    assertThat(sink.ofType("Pod")).hasSize(1);

    hub.failing.addAll(List.of(ResourceKind.POD, ResourceKind.DEPLOYMENT));
    var failed = collector.collectWorkloads();
    assertThat(failed.status()).isEqualTo(SyncStatus.FAILED);
    assertThat(failed.errors()).singleElement().asString().contains("shop/Pod").contains("504");
    assertThat(runs.last().status()).isEqualTo(SyncStatus.FAILED);
  }

  @Test
  void skipsItemsWithoutUidOrResourceVersion() {
    hub.items.put(ResourceKind.POD, List.of(json("{\"metadata\":{\"name\":\"broken\"}}")));

    collector.collectWorkloads();

    assertThat(sink.ofType("Pod")).isEmpty();
    // An item without uid does not affect "which uids exist": the list is recorded as empty
    assertThat(sink.ofType("RESOURCE_LIST").getFirst().payload().path("uids")).isEmpty();
  }

  @Test
  void siteSnapshotOmitsMissingFields() {
    collector.collectSites();

    var e = sink.ofType("SITE_SNAPSHOT").getFirst();
    assertThat(e.provider()).isEqualTo("K8S");
    assertThat(e.payload().at("/items/0/name").asString()).isEqualTo("A 廠");
    assertThat(e.payload().at("/items/0").has("region")).isFalse();
    assertThat(runs.last().provider()).isEqualTo("K8S_SITES");
  }
}
