package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.support.DatabaseTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Import({JpaSiteStore.class, JpaClusterStore.class})
class JpaClusterStoreIT extends DatabaseTest {

  @Autowired SiteStore sites;
  @Autowired ClusterStore clusters;
  @Autowired PlatformTransactionManager transactionManager;

  String site() {
    return "S-" + run;
  }

  String ext() {
    return "cid-" + run;
  }

  ClusterSnapshot snapshot(String name, String server) {
    return new ClusterSnapshot(site(), ext(), name, server);
  }

  @Test
  void ensureExistsCreatesWithADefaultCodeLinkedToTheSiteAndDoesNotOverwrite() {
    sites.ensureExists(site());
    clusters.ensureExists(snapshot("Plant-A", "https://k8s.a:6443"));

    var r =
        row(
            "SELECT c.code, c.name, c.provider, c.argo_server_url, s.code AS site_code"
                + " FROM clusters c JOIN sites s ON s.id=c.site_id WHERE c.external_id=?",
            ext());
    assertThat(r.get("code")).isEqualTo((site() + "-Plant-A").toLowerCase());
    assertThat(r.get("name")).isEqualTo("Plant-A");
    assertThat(r.get("provider")).isEqualTo("ONPREM");
    assertThat(r.get("argo_server_url")).isEqualTo("https://k8s.a:6443");
    assertThat(r.get("site_code")).isEqualTo(site());

    clusters.ensureExists(snapshot("Renamed", null));
    assertThat(row("SELECT name FROM clusters WHERE external_id=?", ext()).get("name"))
        .isEqualTo("Plant-A");
    assertThat(count("SELECT count(*) FROM clusters WHERE external_id=?", ext())).isEqualTo(1);
  }

  @Test
  void upsertUpdatesNameAndServerButNeverTheManuallyMaintainedCode() {
    sites.ensureExists(site());
    clusters.ensureExists(snapshot("plant-a", "https://k8s.a:6443"));
    jdbc.sql("UPDATE clusters SET code=?, environment='prod' WHERE external_id=?")
        .params("aks-prod-" + run, ext())
        .update();

    clusters.upsert(snapshot("plant-a-renamed", null)); // a null server keeps the existing one

    var r =
        row(
            "SELECT code, name, environment, argo_server_url FROM clusters WHERE external_id=?",
            ext());
    assertThat(r.get("name")).isEqualTo("plant-a-renamed");
    assertThat(r.get("code")).isEqualTo("aks-prod-" + run);
    assertThat(r.get("environment")).isEqualTo("prod");
    assertThat(r.get("argo_server_url")).isEqualTo("https://k8s.a:6443");

    clusters.upsert(snapshot("plant-a-renamed", "https://k8s.b:6443"));
    assertThat(
            row("SELECT argo_server_url FROM clusters WHERE external_id=?", ext())
                .get("argo_server_url"))
        .isEqualTo("https://k8s.b:6443");
  }

  @Test
  void setArgoClusterNameMatchesWithinTheSiteIgnoringCaseAndTrailingSlash() {
    sites.ensureExists(site());
    clusters.ensureExists(snapshot("plant-a", "https://K8s.A:6443"));

    int updated = clusters.setArgoClusterName(site(), "https://k8s.a:6443/", "argo-plant-a");

    assertThat(updated).isEqualTo(1);
    assertThat(
            row("SELECT argo_cluster_name FROM clusters WHERE external_id=?", ext())
                .get("argo_cluster_name"))
        .isEqualTo("argo-plant-a");
    // the name is already the same → nothing to update
    assertThat(clusters.setArgoClusterName(site(), "https://k8s.a:6443", "argo-plant-a")).isZero();
  }

  @Test
  void setArgoClusterNameDoesNotTouchOtherSitesOrUnknownServers() {
    sites.ensureExists(site());
    clusters.ensureExists(snapshot("plant-a", "https://k8s.a:6443"));

    assertThat(clusters.setArgoClusterName("OTHER-" + run, "https://k8s.a:6443", "x")).isZero();
    assertThat(clusters.setArgoClusterName(site(), "https://unknown:6443", "x")).isZero();
    assertThat(
            row("SELECT argo_cluster_name FROM clusters WHERE external_id=?", ext())
                .get("argo_cluster_name"))
        .isNull();
  }

  /**
   * The normalization handlers for cluster lists, resources and events can all create the same
   * cluster at the same moment, in different transactions. clusters has two unique keys (code and
   * external_id) and ON CONFLICT names only one of them, so without serializing the writers
   * PostgreSQL sometimes fails the loser with a violation of the other key. Many rounds of two
   * simultaneous writers make that window likely to show up.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void twoTransactionsCreatingTheSameClusterAtTheSameTimeDoNotFail() throws Exception {
    var tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> sites.ensureExists(site()));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 300; round++) {
        String externalId = "race-" + run + "-" + round;
        String name = "c" + round;
        var barrier = new CyclicBarrier(2);
        List<Future<?>> writers = new ArrayList<>();
        writers.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  tx.executeWithoutResult(
                      status ->
                          clusters.ensureExists(
                              new ClusterSnapshot(site(), externalId, name, null)));
                  return null;
                }));
        writers.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  tx.executeWithoutResult(
                      status ->
                          clusters.upsert(
                              new ClusterSnapshot(site(), externalId, name, "https://k8s.a:6443")));
                  return null;
                }));
        for (Future<?> writer : writers) {
          writer.get(30, TimeUnit.SECONDS); // rethrows the violation if there was one
        }
      }
      assertThat(
              count("SELECT count(*) FROM clusters WHERE external_id LIKE ?", "race-" + run + "-%"))
          .isEqualTo(300);
    } finally {
      pool.shutdownNow();
      // this test commits for real (the base class rolls back), so clean up after it
      tx.executeWithoutResult(
          status -> {
            jdbc.sql("DELETE FROM clusters WHERE external_id LIKE ?")
                .params("race-" + run + "-%")
                .update();
            jdbc.sql("DELETE FROM sites WHERE code = ?").params(site()).update();
          });
    }
  }
}
