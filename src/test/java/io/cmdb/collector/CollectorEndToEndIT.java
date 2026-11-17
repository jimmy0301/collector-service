package io.cmdb.collector;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cmdb.collector.ado.application.AdoCollector;
import io.cmdb.collector.argo.application.ArgoCollector;
import io.cmdb.collector.k8s.application.K8sCollector;
import io.cmdb.collector.support.Fixtures;
import io.cmdb.collector.support.PipelineTest;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.trigger.application.CollectionTrigger;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End to end with both integration services replaced by WireMock: collectors poll over real HTTP →
 * raw events are landed → the normalization pipeline runs → domain tables are filled. The
 * schedulers are not started (their delays are 1h in tests); the collectors are called directly in
 * the order the schedulers would run them.
 */
class CollectorEndToEndIT extends PipelineTest {

  @RegisterExtension
  static WireMockExtension hub =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void hubs(DynamicPropertyRegistry registry) {
    registry.add("collector.argo.base-url", hub::baseUrl);
    registry.add("collector.argo.token", () -> "argo-token");
    registry.add("collector.k8s.base-url", hub::baseUrl);
    registry.add("collector.k8s.token", () -> "k8s-token");
    registry.add("collector.ado.base-url", hub::baseUrl);
    registry.add("collector.ado.organization", () -> "org");
    registry.add("collector.ado.projects", () -> "e2e-project");
    registry.add("collector.ado.token", () -> "ado-pat");
  }

  @Autowired ArgoCollector argo;
  @Autowired K8sCollector k8s;
  @Autowired AdoCollector ado;
  @Autowired CollectionTrigger trigger;

  static MappingBuilder json(MappingBuilder stub, String body) {
    stub.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body));
    return stub;
  }

  static String items(String... items) {
    return "{\"metadata\":{\"continue\":\"\"},\"items\":[" + String.join(",", items) + "]}";
  }

  void stubHubs() {
    String argoBase = "/sites/" + site + "/argocd/api/v1";
    String k8sBase = "/sites/" + site + "/clusters/" + clusterId;

    // Argo hub (fixtures written after INTEGRATION_API_SPEC.md chapter 4)
    hub.stubFor(
        json(
            get(urlPathEqualTo("/sites")), "[{\"code\":\"" + site + "\",\"name\":\"E2E Plant\"}]"));
    hub.stubFor(
        json(
            get(urlPathEqualTo(argoBase + "/applications")),
            Fixtures.load("argo/applications.json")));
    hub.stubFor(
        json(
            get(urlPathEqualTo(argoBase + "/applications/shop/resource-tree")),
            Fixtures.load("argo/resource-tree.json")
                .replace("dep-1", depUid)
                .replace("rs-1", rsUid)));
    hub.stubFor(
        json(get(urlPathEqualTo(argoBase + "/clusters")), Fixtures.load("argo/clusters.json")));

    // Azure DevOps Release: two deployments (releases 310 and 311) of the fixture
    hub.stubFor(
        json(
            get(urlPathEqualTo("/org/e2e-project/_apis/release/deployments")),
            Fixtures.load("ado/deployments.json")));
    hub.stubFor(
        json(
            get(urlPathEqualTo("/org/e2e-project/_apis/release/releases/310")),
            Fixtures.load("ado/release-310.json")));
    hub.stubFor(
        json(
            get(urlPathEqualTo("/org/e2e-project/_apis/release/releases/311")),
            Fixtures.load("ado/release-310.json").replace("\"id\": 310,", "\"id\": 311,")));

    // K8s integration service: the cluster matches the Argo destination by server
    hub.stubFor(
        json(
            get(urlPathEqualTo("/sites/" + site + "/clusters")),
            "[{\"id\":\"%s\",\"name\":\"plant-a\",\"server\":\"https://k8s.plant-a:6443\"}]"
                .formatted(clusterId)));
    hub.stubFor(
        json(
            get(urlPathEqualTo(k8sBase + "/apis/apps/v1/namespaces/shop/deployments")),
            items(payloads.deployment("1"))));
    hub.stubFor(
        json(
            get(urlPathEqualTo(k8sBase + "/apis/apps/v1/namespaces/shop/replicasets")),
            items(payloads.replicaSet("1"))));
    hub.stubFor(
        json(
            get(urlPathEqualTo(k8sBase + "/api/v1/namespaces/shop/pods")),
            items(payloads.pod("1", "Running", null))));
    hub.stubFor(
        json(
            get(urlPathEqualTo(k8sBase + "/api/v1/namespaces/shop/events")),
            items(payloads.event("1", 3, "2026-11-14T00:00:30Z", "BackOff"))));
  }

  @Test
  void pollingBothHubsFillsTheDomainTablesAndRecordsFreshness() {
    stubHubs();

    // the order in which the schedulers run them: site / cluster lists, then Argo (which defines
    // the scope), then K8s
    k8s.collectSites();
    k8s.collectClusters();
    argo.collectSites();
    argo.collectApplications();
    argo.collectClusters();
    k8s.collectWorkloads();
    // An Event is only linked to its Pod when the Pod is already known, and normalization runs
    // asynchronously, so wait for the workloads before the events arrive (as they do in practice:
    // an Event describes an object that already exists)
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        count(
                            "SELECT count(*) FROM k8s_resources r JOIN clusters c ON c.id=r.cluster_id WHERE c.external_id=?",
                            clusterId))
                    .isEqualTo(3));
    k8s.collectEvents();
    ado.collectReleases();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(
                      count(
                          "SELECT count(*) FROM k8s_resources r JOIN clusters c ON c.id=r.cluster_id WHERE c.external_id=?",
                          clusterId))
                  .isEqualTo(3); // Deployment, ReplicaSet, Pod
              assertThat(
                      count("SELECT count(*) FROM k8s_events WHERE event_uid=?", payloads.eventUid))
                  .isEqualTo(1);
              assertThat(
                      row("SELECT argo_cluster_name FROM clusters WHERE external_id=?", clusterId)
                          .get("argo_cluster_name"))
                  .isEqualTo("plant-a");
            });

    // site name comes from the hubs; the cluster is linked to it
    assertThat(row("SELECT name FROM sites WHERE code=?", site).get("name")).isEqualTo("E2E Plant");
    assertThat(
            count(
                "SELECT count(*) FROM clusters c JOIN sites s ON s.id=c.site_id WHERE s.code=? AND c.external_id=?",
                site,
                clusterId))
        .isEqualTo(1);
    // the event is linked to the Pod, and the Pod's containers and image artifact were normalized
    assertThat(
            count(
                "SELECT count(*) FROM k8s_events e JOIN k8s_resources r ON r.id=e.k8s_resource_id WHERE e.event_uid=? AND r.resource_uid=?",
                payloads.eventUid,
                podUid))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM k8s_pod_containers c JOIN k8s_resources r ON r.id=c.k8s_resource_id WHERE r.resource_uid=?",
                podUid))
        .isEqualTo(3); // app, proxy, init
    // the Pod's key state was recorded as the current history row
    assertThat(
            count(
                "SELECT count(*) FROM k8s_resource_history h JOIN k8s_resources r ON r.id=h.k8s_resource_id WHERE r.resource_uid=? AND h.valid_to IS NULL",
                podUid))
        .isEqualTo(1);
    // Azure DevOps: the releases and the deployments (release + stage + attempt) were normalized
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        count(
                            "SELECT count(*) FROM release_deployments d JOIN releases r ON r.id=d.release_id WHERE r.project='e2e-project'"))
                    .isEqualTo(2));
    var release =
        row("SELECT * FROM releases WHERE project='e2e-project' AND external_release_id='310'");
    assertThat(release.get("name")).isEqualTo("Release-310");
    assertThat(release.get("artifact_build_ref")).isEqualTo("5521");
    assertThat(release.get("created_by")).isEqualTo("Alice Chen");
    var uat =
        row(
            "SELECT d.* FROM release_deployments d JOIN releases r ON r.id=d.release_id WHERE r.external_release_id='310' AND r.project='e2e-project'");
    assertThat(uat.get("environment_name")).isEqualTo("uat");
    assertThat(uat.get("deployment_status")).isEqualTo("succeeded");
    assertThat(uat.get("requested_by")).isEqualTo("Alice Chen");
    assertThat(
            count(
                "SELECT count(*) FROM integration_sync_runs WHERE status='SUCCESS' AND provider='ADO:e2e-project'"))
        .isEqualTo(1);
    // Argo: the Application row (cluster resolved by destination server) and its resource-tree
    // linked the K8s resources it manages
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        count(
                            "SELECT count(*) FROM k8s_resources r JOIN argo_applications a ON a.id=r.argo_application_id WHERE a.external_uid='app-uid-1' AND r.resource_uid IN (?, ?)",
                            depUid,
                            rsUid))
                    .isEqualTo(2));
    var argoApp =
        row(
            "SELECT a.*, c.external_id AS cluster_external_id FROM argo_applications a LEFT JOIN clusters c ON c.id=a.cluster_id WHERE a.external_uid='app-uid-1'");
    assertThat(argoApp.get("name")).isEqualTo("shop");
    assertThat(argoApp.get("argo_server")).isEqualTo(site);
    assertThat(argoApp.get("sync_status")).isEqualTo("Synced");
    assertThat(argoApp.get("health_status")).isEqualTo("Healthy");
    assertThat(argoApp.get("destination_namespace")).isEqualTo("shop");
    assertThat(argoApp.get("sources").toString()).contains("git.example/shop-manifests");
    assertThat(argoApp.get("cluster_external_id")).isEqualTo(clusterId);
    assertThat(
            row("SELECT argo_health_status FROM k8s_resources WHERE resource_uid=?", depUid)
                .get("argo_health_status"))
        .isEqualTo("Healthy");
    // the Application's sync operation became a deployment (the Argo-side fact)
    var deployment =
        row(
            "SELECT d.* FROM deployments d JOIN argo_applications a ON a.id=d.argo_application_id WHERE a.external_uid='app-uid-1'");
    assertThat(deployment.get("external_operation_key"))
        .isEqualTo("app-uid-1:2026-11-14T01:00:00Z");
    assertThat(deployment.get("deployment_status")).isEqualTo("SUCCEEDED");
    assertThat(deployment.get("trigger_type")).isEqualTo("ARGOCD");
    assertThat(deployment.get("git_revision")).isEqualTo("abc123");
    assertThat(deployment.get("status_message")).isEqualTo("successfully synced (all tasks run)");
    assertThat(deployment.get("metadata").toString())
        .contains("release-bot")
        .contains("release-id");
    // every K8s raw event of this site's cluster was processed (Argo Application / resource-tree
    // have no handler yet)
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        count(
                            "SELECT count(*) FROM integration_raw_events WHERE provider='K8S' AND process_status<>'PROCESSED' AND external_event_id LIKE ?",
                            "%" + clusterId + "%"))
                    .isZero());
    assertThat(
            count(
                "SELECT count(*) FROM integration_raw_events WHERE provider='ARGO' AND event_type='APPLICATION_SNAPSHOT' AND external_event_id LIKE ?",
                "%" + site + "%"))
        .isEqualTo(1);
    // freshness: each poll recorded a successful sync run
    assertThat(
            count(
                "SELECT count(*) FROM integration_sync_runs WHERE status='SUCCESS' AND provider IN (?, ?, ?)",
                "ARGO:" + site,
                "K8S:" + site + ":plant-a",
                "K8S_EVENT:" + site + ":plant-a"))
        .isEqualTo(3);
  }

  @Test
  void theTriggerApiIsWiredToTheRealCollectorsInRunOrder() {
    stubHubs();

    assertThat(trigger.available().keySet()).containsExactly("argo", "k8s", "ado");
    assertThat(trigger.available().get("argo"))
        .containsExactly("sites", "clusters", "applications");

    var report = trigger.run("argo", "sites");

    assertThat(report.jobs()).hasSize(1);
    assertThat(report.jobs().getFirst().status()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(report.jobs().getFirst().runs()).isEqualTo(1);
  }
}
