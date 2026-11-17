package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.argo.ArgoApplicationSnapshot;
import io.cmdb.collector.inventory.domain.argo.ArgoApplicationStore;
import io.cmdb.collector.inventory.domain.argo.ArgoResourceNode;
import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.support.DatabaseTest;
import io.cmdb.collector.support.Fakes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({
  JpaSiteStore.class,
  JpaClusterStore.class,
  JpaK8sResourceStore.class,
  JpaK8sResourceHistoryStore.class,
  JpaArgoApplicationStore.class
})
class JpaArgoApplicationStoreIT extends DatabaseTest {

  static final Instant T1 = Instant.parse("2026-11-14T01:00:00Z");
  static final Instant T2 = Instant.parse("2026-11-14T02:00:00Z");
  static final Instant T3 = Instant.parse("2026-11-14T03:00:00Z");

  @Autowired SiteStore sites;
  @Autowired ClusterStore clusters;
  @Autowired K8sResourceStore resources;
  @Autowired ArgoApplicationStore applications;

  String site;
  String uid;

  @BeforeEach
  void init() {
    site = "S-" + run;
    uid = "app-" + run;
    sites.ensureExists(site);
  }

  void cluster(String name, String server) {
    clusters.upsert(new ClusterSnapshot(site, "cid-" + run + name, name, server));
  }

  ArgoApplicationSnapshot app(
      String server, String destName, String sync, String health, Instant at) {
    return new ArgoApplicationSnapshot(
        site,
        uid,
        "shop",
        "default",
        server,
        destName,
        "shop",
        Fakes.json("[{\"repoURL\":\"https://git/x\"}]"),
        sync,
        health,
        true,
        Fakes.json("[\"abc\"]"),
        at);
  }

  Map<String, Object> appRow() {
    return row("SELECT * FROM argo_applications WHERE external_uid=?", uid);
  }

  // ---------- upsert ----------

  @Test
  void upsertInsertsAndTheSameApplicationUpdatesTheSameRow() {
    long id = applications.upsert(app("https://k:6443", null, "OutOfSync", "Progressing", T1));
    long again = applications.upsert(app("https://k:6443", null, "Synced", "Healthy", T2));

    assertThat(again).isEqualTo(id);
    var r = appRow();
    assertThat(r.get("sync_status")).isEqualTo("Synced");
    assertThat(r.get("health_status")).isEqualTo("Healthy");
    assertThat(r.get("argo_server")).isEqualTo(site);
    assertThat(r.get("auto_sync")).isEqualTo(true);
    assertThat(r.get("sources").toString()).contains("https://git/x");
    assertThat(r.get("sync_revisions").toString()).contains("abc");
    assertThat(instant("SELECT observed_at FROM argo_applications WHERE id=?", id)).isEqualTo(T2);
  }

  @Test
  void anOlderObservationNeverOverwritesANewerOneButStillReturnsTheId() {
    long id = applications.upsert(app("https://k:6443", null, "Synced", "Healthy", T3));

    long old = applications.upsert(app("https://k:6443", null, "OutOfSync", "Degraded", T2));

    assertThat(old).isEqualTo(id);
    assertThat(appRow().get("sync_status")).isEqualTo("Synced");
    assertThat(appRow().get("health_status")).isEqualTo("Healthy");
  }

  @Test
  void aStubIsCreatedOnceAndOverwrittenByTheRealApplication() {
    long stub = applications.ensureStub(site, uid, "shop");
    assertThat(applications.ensureStub(site, uid, "other")).isEqualTo(stub);
    assertThat(appRow().get("name")).isEqualTo("shop"); // never overwritten by another stub
    assertThat(appRow().get("sync_status")).isNull();

    long real = applications.upsert(app("https://k:6443", null, "Synced", "Healthy", T1));

    assertThat(real).isEqualTo(stub);
    assertThat(appRow().get("sync_status")).isEqualTo("Synced");
  }

  // ---------- cluster resolution ----------

  @Test
  void theClusterIsMatchedByServerIgnoringCaseAndTrailingSlash() {
    cluster("plant-a", "https://K.A:6443");

    applications.upsert(app("https://k.a:6443/", null, "Synced", "Healthy", T1));

    assertThat(
            count(
                "SELECT count(*) FROM argo_applications a JOIN clusters c ON c.id=a.cluster_id WHERE a.external_uid=? AND c.external_id=?",
                uid,
                "cid-" + run + "plant-a"))
        .isEqualTo(1);
  }

  @Test
  void theClusterIsMatchedByArgoClusterNameWhenThereIsNoServer() {
    cluster("plant-a", "https://k.a:6443");
    clusters.setArgoClusterName(site, "https://k.a:6443", "argo-a");

    applications.upsert(app(null, "argo-a", "Synced", "Healthy", T1));

    assertThat(appRow().get("cluster_id")).isNotNull();
  }

  @Test
  void aClusterOfAnotherSiteIsNeverMatched() {
    sites.ensureExists("other-" + run);
    clusters.upsert(
        new ClusterSnapshot("other-" + run, "cid-other-" + run, "x", "https://k.a:6443"));

    applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));

    assertThat(appRow().get("cluster_id")).isNull();
  }

  @Test
  void aKnownClusterIsKeptWhenALaterEventCannotResolveOne() {
    cluster("plant-a", "https://k.a:6443");
    applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));

    applications.upsert(app("https://unknown:6443", null, "Synced", "Healthy", T2));

    assertThat(appRow().get("cluster_id")).isNotNull();
  }

  @Test
  void linkUnmappedClustersFillsInClustersRegisteredAfterTheApplication() {
    applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));
    assertThat(appRow().get("cluster_id")).isNull();

    cluster("plant-a", "https://k.a:6443");
    assertThat(applications.linkUnmappedClusters(site)).isEqualTo(1);

    assertThat(appRow().get("cluster_id")).isNotNull();
    assertThat(applications.linkUnmappedClusters(site)).isZero(); // nothing left to link
  }

  // ---------- linking resources ----------

  void pod(String podUid) {
    resources.upsert(
        new K8sResourceSnapshot(
            "cid-" + run + "plant-a",
            podUid,
            "shop",
            "Pod",
            "p-" + podUid,
            "v1",
            0,
            "Running",
            null,
            null,
            null,
            null,
            T1,
            null));
  }

  Map<String, Object> resource(String resourceUid) {
    return row("SELECT * FROM k8s_resources WHERE resource_uid=?", resourceUid);
  }

  static ArgoResourceNode node(String uid, String kind, String health) {
    return new ArgoResourceNode(uid, kind, "shop", "n-" + uid, health);
  }

  @Test
  void linkResourcesSetsTheApplicationAndTheArgoHealthOnExistingResources() {
    cluster("plant-a", "https://k.a:6443");
    pod("p1-" + run);
    long appId = applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));

    int changed = applications.linkResources(appId, List.of(node("p1-" + run, "Pod", "Healthy")));

    assertThat(changed).isEqualTo(1);
    assertThat(((Number) resource("p1-" + run).get("argo_application_id")).longValue())
        .isEqualTo(appId);
    assertThat(resource("p1-" + run).get("argo_health_status")).isEqualTo("Healthy");
    assertThat(resource("p1-" + run).get("phase")).isEqualTo("Running"); // the rest is untouched
  }

  @Test
  void aCollectedResourceK8sHasNotReportedYetGetsAPlaceholderThatTheRealDataKeepsTheLinkOf() {
    cluster("plant-a", "https://k.a:6443");
    long appId = applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));

    applications.linkResources(appId, List.of(node("late-" + run, "Pod", "Progressing")));

    var stub = resource("late-" + run);
    assertThat(stub.get("resource_kind")).isEqualTo("Pod");
    assertThat(stub.get("resource_name")).isEqualTo("n-late-" + run);
    assertThat(((Number) stub.get("argo_application_id")).longValue()).isEqualTo(appId);

    pod("late-" + run); // K8s reports it afterwards and overwrites the placeholder
    var real = resource("late-" + run);
    assertThat(real.get("phase")).isEqualTo("Running");
    assertThat(real.get("api_version")).isEqualTo("v1");
    assertThat(((Number) real.get("argo_application_id")).longValue()).isEqualTo(appId);
    assertThat(real.get("argo_health_status")).isEqualTo("Progressing");
    assertThat(count("SELECT count(*) FROM k8s_resources WHERE resource_uid=?", "late-" + run))
        .isEqualTo(1);
  }

  @Test
  void kindsThatAreNotCollectedFromK8sNeverGetAPlaceholder() {
    cluster("plant-a", "https://k.a:6443");
    long appId = applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));

    int changed =
        applications.linkResources(
            appId,
            List.of(
                node("svc-" + run, "Service", "Healthy"), node("cm-" + run, "ConfigMap", null)));

    assertThat(changed).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM k8s_resources WHERE resource_uid IN (?, ?)",
                "svc-" + run,
                "cm-" + run))
        .isZero();
  }

  @Test
  void linkingAgainWithTheSameValuesChangesNothingAndNewHealthUpdatesIt() {
    cluster("plant-a", "https://k.a:6443");
    pod("p1-" + run);
    long appId = applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));
    applications.linkResources(appId, List.of(node("p1-" + run, "Pod", "Healthy")));

    assertThat(applications.linkResources(appId, List.of(node("p1-" + run, "Pod", "Healthy"))))
        .isZero();
    assertThat(applications.linkResources(appId, List.of(node("p1-" + run, "Pod", "Degraded"))))
        .isEqualTo(1);

    assertThat(resource("p1-" + run).get("argo_health_status")).isEqualTo("Degraded");
  }

  @Test
  void aResourceInAnotherClusterThanTheApplicationIsNotLinked() {
    cluster("plant-a", "https://k.a:6443");
    long appId = applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));
    cluster("plant-b", "https://k.b:6443");
    resources.upsert(
        new K8sResourceSnapshot(
            "cid-" + run + "plant-b",
            "pb-" + run,
            "shop",
            "Pod",
            "p",
            "v1",
            0,
            null,
            null,
            null,
            null,
            null,
            T1,
            null));

    applications.linkResources(appId, List.of(node("pb-" + run, "Pod", "Healthy")));

    // the row in the other cluster is untouched (the Application's own cluster gets a placeholder)
    assertThat(
            row(
                    "SELECT argo_application_id FROM k8s_resources r JOIN clusters c ON c.id=r.cluster_id WHERE r.resource_uid=? AND c.external_id=?",
                    "pb-" + run,
                    "cid-" + run + "plant-b")
                .get("argo_application_id"))
        .isNull();
  }

  @Test
  void anApplicationWithoutAClusterLinksExistingResourcesByUidAndCreatesNothing() {
    cluster("plant-a", "https://k.a:6443");
    pod("p1-" + run);
    long appId = applications.upsert(app("https://unknown:6443", null, "Synced", "Healthy", T1));

    int changed =
        applications.linkResources(
            appId, List.of(node("p1-" + run, "Pod", null), node("other-" + run, "Pod", "Healthy")));

    assertThat(changed).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM k8s_resources WHERE resource_uid=?", "other-" + run))
        .isZero();
  }

  @Test
  void anEmptyNodeListChangesNothing() {
    long appId = applications.upsert(app("https://k.a:6443", null, "Synced", "Healthy", T1));

    assertThat(applications.linkResources(appId, List.of())).isZero();
  }
}
