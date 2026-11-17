package io.cmdb.collector.argo.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.argo.ArgoScopeObserved;
import io.cmdb.collector.argo.Destination;
import io.cmdb.collector.argo.domain.ArgoHub;
import io.cmdb.collector.argo.domain.Site;
import io.cmdb.collector.rawevent.application.RawEventRecorder;
import io.cmdb.collector.support.Fakes.InMemoryRuns;
import io.cmdb.collector.support.Fakes.InMemorySink;
import io.cmdb.collector.syncrun.application.SyncRunTracker;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ArgoCollectorTest {

  static class FakeHub implements ArgoHub {
    JsonNode applications = json("{\"items\":[]}");
    JsonNode tree =
        json(
            "{\"nodes\":[{\"uid\":\"p1\",\"kind\":\"Pod\",\"name\":\"a\",\"info\":[{\"x\":1}],\"resourceVersion\":\"9\"}],\"orphanedNodes\":[]}");
    boolean treeFails;
    int treeCalls;
    final List<String> treeRequests = new ArrayList<>();

    @Override
    public List<String> sites() {
      return List.of("PLANT-A");
    }

    @Override
    public List<Site> siteDetails() {
      return List.of(new Site("PLANT-A", "A 廠", "north"));
    }

    @Override
    public JsonNode applications(String site) {
      return applications;
    }

    @Override
    public JsonNode resourceTree(String site, String appName, String appNamespace, String project) {
      treeCalls++;
      treeRequests.add(site + "/" + appName + "/" + appNamespace + "/" + project);
      if (treeFails) {
        throw new IllegalStateException("502 upstream down");
      }
      return tree;
    }

    @Override
    public JsonNode clusters(String site) {
      return json(
          "{\"items\":[{\"name\":\"in-cluster\",\"server\":\"https://k\",\"config\":{\"bearerToken\":\"SECRET\"}}]}");
    }
  }

  static String app(String health, String resourceVersion, String reconciledAt) {
    return """
        {"metadata":{"uid":"u-1","name":"shop","namespace":"argocd","resourceVersion":"%s"},
         "spec":{"project":"default","destination":{"server":"https://k","namespace":"shop"}},
         "status":{"health":{"status":"%s"},"sync":{"status":"Synced","revision":"abc"},
           "reconciledAt":"%s","resources":[{"kind":"Deployment","name":"big-list"}],
           "operationState":{"phase":"Succeeded","startedAt":"2026-11-09T01:00:00Z",
             "syncResult":{"revision":"abc","resources":[{"x":1}]}}}}
        """
        .formatted(resourceVersion, health, reconciledAt);
  }

  FakeHub hub = new FakeHub();
  InMemorySink sink = new InMemorySink();
  InMemoryRuns runs = new InMemoryRuns();
  List<Object> published = new ArrayList<>();
  AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T00:00:00Z"));
  ArgoCollector collector;

  @BeforeEach
  void setUp() {
    Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    collector =
        new ArgoCollector(
            hub,
            new RawEventRecorder(sink, event -> {}, new SimpleMeterRegistry(), 1000),
            new SyncRunTracker(runs, Clock.systemUTC()),
            published::add,
            clock,
            Duration.ofMinutes(5));
  }

  void apps(String... appJson) {
    hub.applications = json("{\"items\":[" + String.join(",", appJson) + "]}");
  }

  @Test
  void recordsApplicationAndTreeWithSiteEnvelope() {
    apps(app("Healthy", "1", "t1"));

    collector.collectApplications();

    var appEvent = sink.ofType("APPLICATION_SNAPSHOT").getFirst();
    assertThat(appEvent.provider()).isEqualTo("ARGO");
    assertThat(appEvent.externalEventId()).startsWith("argo:PLANT-A:app:u-1:");
    assertThat(appEvent.orderingKey()).isEqualTo("argo:PLANT-A:u-1");
    assertThat(appEvent.payload().path("site").asString()).isEqualTo("PLANT-A");
    assertThat(appEvent.payload().at("/resource/status/health/status").asString())
        .isEqualTo("Healthy");
    // The full resource list is not landed (it comes from the resource-tree instead)
    assertThat(appEvent.payload().at("/resource/status").has("resources")).isFalse();
    assertThat(appEvent.payload().at("/resource/status/operationState/syncResult").has("resources"))
        .isFalse();

    var treeEvent = sink.ofType("RESOURCE_TREE_SNAPSHOT").getFirst();
    assertThat(treeEvent.orderingKey()).isEqualTo(appEvent.orderingKey());
    JsonNode node = treeEvent.payload().at("/resource/nodes/0");
    assertThat(node.path("uid").asString()).isEqualTo("p1");
    assertThat(node.has("info")).isFalse();
    assertThat(node.has("resourceVersion")).isFalse();
    assertThat(hub.treeRequests).containsExactly("PLANT-A/shop/argocd/default");

    assertThat(runs.last().provider()).isEqualTo("ARGO:PLANT-A");
    assertThat(runs.last().status()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(runs.last().records()).isEqualTo(1);
  }

  @Test
  void unchangedApplicationIsNotRecordedAgainEvenIfVolatileFieldsChange() {
    apps(app("Healthy", "1", "t1"));
    collector.collectApplications();
    int events = sink.events.size();

    apps(app("Healthy", "2", "t2")); // only resourceVersion and reconciledAt changed
    now.set(now.get().plusSeconds(30));
    collector.collectApplications();

    assertThat(sink.events).hasSize(events);
    assertThat(hub.treeCalls)
        .isEqualTo(1); // unchanged and tree-interval not reached → not fetched again
  }

  @Test
  void changedApplicationCreatesNewEventAndRefetchesTree() {
    apps(app("Healthy", "1", "t1"));
    collector.collectApplications();

    apps(app("Degraded", "2", "t2"));
    collector.collectApplications();

    assertThat(sink.ofType("APPLICATION_SNAPSHOT")).hasSize(2);
    assertThat(hub.treeCalls).isEqualTo(2);
  }

  @Test
  void treeIsRefreshedAfterTreeInterval() {
    apps(app("Healthy", "1", "t1"));
    collector.collectApplications();

    now.set(now.get().plus(Duration.ofMinutes(5)));
    collector.collectApplications();

    assertThat(hub.treeCalls).isEqualTo(2);
    assertThat(sink.ofType("RESOURCE_TREE_SNAPSHOT"))
        .hasSize(1); // same content in the same hour, event deduplicated
  }

  @Test
  void anUnchangedTreeIsLandedAgainInTheNextHourSoLateK8sResourcesGetLinked() {
    apps(app("Healthy", "1", "t1"));
    collector.collectApplications();

    now.set(now.get().plus(Duration.ofHours(1)));
    collector.collectApplications();

    assertThat(sink.ofType("RESOURCE_TREE_SNAPSHOT")).hasSize(2);
  }

  @Test
  void theTreeEventSaysWhichApplicationItBelongsTo() {
    apps(app("Healthy", "1", "t1"));

    collector.collectApplications();

    var payload = sink.ofType("RESOURCE_TREE_SNAPSHOT").getFirst().payload();
    assertThat(payload.at("/application/uid").asString()).isEqualTo("u-1");
    assertThat(payload.at("/application/name").asString()).isEqualTo("shop");
    assertThat(payload.path("site").asString()).isEqualTo("PLANT-A");
  }

  @Test
  void treeFailureMakesRunPartialButKeepsApplicationEvent() {
    hub.treeFails = true;
    apps(app("Healthy", "1", "t1"));

    var result = collector.collectApplications();

    assertThat(result.status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(result.runs()).isEqualTo(1);
    assertThat(result.errors())
        .singleElement()
        .asString()
        .startsWith("ARGO:PLANT-A: ")
        .contains("resource-tree shop")
        .contains("502");
    assertThat(sink.ofType("APPLICATION_SNAPSHOT")).hasSize(1);
    assertThat(runs.last().status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(runs.last().error()).contains("resource-tree shop").contains("502");
  }

  @Test
  void publishesObservedScopeAndExcludesAppOfApps() {
    String appOfApps =
        """
        {"metadata":{"uid":"u-2","name":"root","namespace":"argocd"},
         "spec":{"project":"default","destination":{"server":"https://kubernetes.default.svc","namespace":"argocd"}},
         "status":{"summary":{"isAppOfApps":true}}}
        """;
    apps(app("Healthy", "1", "t1"), appOfApps);

    collector.collectApplications();

    assertThat(published).hasSize(1);
    ArgoScopeObserved event = (ArgoScopeObserved) published.getFirst();
    assertThat(event.site()).isEqualTo("PLANT-A");
    assertThat(event.destinations()) // app-of-apps (argocd namespace) is not in scope
        .containsExactly(new Destination("https://k", null, "shop"));
    assertThat(sink.ofType("APPLICATION_SNAPSHOT"))
        .hasSize(2); // app-of-apps is still landed and excluded by the Processor
  }

  @Test
  void upstreamFailureIsRecordedAsFailedRun() {
    hub.applications = null; // make the hub throw an NPE to simulate an upstream error

    var result = collector.collectApplications();

    assertThat(result.status()).isEqualTo(SyncStatus.FAILED);
    assertThat(runs.last().status()).isEqualTo(SyncStatus.FAILED);
    assertThat(sink.events).isEmpty();
  }

  @Test
  void clusterSnapshotKeepsOnlyNameAndServer() {
    collector.collectClusters();

    var e = sink.ofType("CLUSTER_SNAPSHOT").getFirst();
    assertThat(e.payload().at("/resource/items/0/name").asString()).isEqualTo("in-cluster");
    assertThat(e.payload().toString()).doesNotContain("SECRET").doesNotContain("bearerToken");
    assertThat(runs.last().provider()).isEqualTo("ARGO_CLUSTERS:PLANT-A");
  }

  @Test
  void siteSnapshotCarriesNameAndRegion() {
    collector.collectSites();

    var e = sink.ofType("SITE_SNAPSHOT").getFirst();
    assertThat(e.provider()).isEqualTo("ARGO");
    assertThat(e.payload().at("/items/0/code").asString()).isEqualTo("PLANT-A");
    assertThat(e.payload().at("/items/0/name").asString()).isEqualTo("A 廠");
    assertThat(e.payload().at("/items/0/region").asString()).isEqualTo("north");
    assertThat(runs.last().provider()).isEqualTo("ARGO_SITES");

    collector.collectSites(); // same content → deduplicated
    assertThat(sink.ofType("SITE_SNAPSHOT")).hasSize(1);
  }

  @Test
  void clusterSnapshotIsRegeneratedEveryHourEvenWhenUnchanged() {
    collector.collectClusters();
    collector.collectClusters();
    assertThat(sink.ofType("CLUSTER_SNAPSHOT")).hasSize(1);

    now.set(now.get().plus(Duration.ofHours(1)));
    collector.collectClusters();

    assertThat(sink.ofType("CLUSTER_SNAPSHOT")).hasSize(2);
  }
}
