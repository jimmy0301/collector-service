package io.cmdb.collector.trigger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncResult;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.trigger.domain.CollectionJob;
import io.cmdb.collector.trigger.domain.TriggerBusyException;
import io.cmdb.collector.trigger.domain.UnknownJobException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CollectionTriggerTest {

  final List<String> ran = new ArrayList<>();

  CollectionJob job(String source, String name, SyncStatus status) {
    return CollectionJob.of(
        source,
        name,
        () -> {
          ran.add(source + "/" + name);
          return CollectionResult.none().with(status);
        });
  }

  CollectionTrigger trigger() {
    return new CollectionTrigger(
        List.of(
            job("argo", "sites", SyncStatus.SUCCESS),
            job("argo", "applications", SyncStatus.PARTIAL),
            job("k8s", "workloads", SyncStatus.SUCCESS)));
  }

  @Test
  void listsSourcesAndJobsInRunOrder() {
    assertThat(trigger().available())
        .containsEntry("argo", List.of("sites", "applications"))
        .containsEntry("k8s", List.of("workloads"));
    assertThat(trigger().available().keySet()).containsExactly("argo", "k8s");
  }

  @Test
  void runsOneJob() {
    var report = trigger().run("argo", "applications");

    assertThat(ran).containsExactly("argo/applications");
    assertThat(report.source()).isEqualTo("argo");
    assertThat(report.jobs()).hasSize(1);
    assertThat(report.jobs().getFirst().status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(report.jobs().getFirst().runs()).isEqualTo(1);
    assertThat(report.jobs().getFirst().errors()).isEmpty();
  }

  @Test
  void runsEveryJobOfASourceInOrderWhenNoJobIsNamed() {
    var report = trigger().run("argo", null);

    assertThat(ran).containsExactly("argo/sites", "argo/applications");
    assertThat(report.jobs()).extracting("job").containsExactly("sites", "applications");
  }

  @Test
  void runAllGoesThroughEverySourceInOrder() {
    var reports = trigger().runAll();

    assertThat(ran).containsExactly("argo/sites", "argo/applications", "k8s/workloads");
    assertThat(reports).extracting("source").containsExactly("argo", "k8s");
  }

  @Test
  void aThrowingJobIsReportedAsFailedAndDoesNotStopTheOthers() {
    var trigger =
        new CollectionTrigger(
            List.of(
                CollectionJob.of(
                    "argo",
                    "sites",
                    () -> {
                      throw new IllegalStateException("hub down");
                    }),
                job("argo", "clusters", SyncStatus.SUCCESS)));

    var report = trigger.run("argo", null);

    assertThat(report.jobs().get(0).status()).isEqualTo(SyncStatus.FAILED);
    assertThat(report.jobs().get(0).errors())
        .singleElement()
        .asString()
        .contains("IllegalStateException")
        .contains("hub down");
    assertThat(report.jobs().get(1).status()).isEqualTo(SyncStatus.SUCCESS);
  }

  @Test
  void theReasonsARunFailedAreCarriedIntoTheReport() {
    var trigger =
        new CollectionTrigger(
            List.of(
                CollectionJob.of(
                    "argo",
                    "applications",
                    () ->
                        CollectionResult.none()
                            .with(
                                new SyncResult(
                                    "ARGO:PLANT-A", SyncStatus.PARTIAL, "tree shop: 502"))
                            .with(new SyncResult("ARGO:PLANT-B", SyncStatus.SUCCESS, null)))));

    var job = trigger.run("argo", "applications").jobs().getFirst();

    assertThat(job.status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(job.errors()).containsExactly("ARGO:PLANT-A: tree shop: 502");
  }

  @Test
  void aSourceWithNothingInScopeReportsZeroRuns() {
    var trigger =
        new CollectionTrigger(List.of(CollectionJob.of("ado", "releases", CollectionResult::none)));

    var job = trigger.run("ado", "releases").jobs().getFirst();

    assertThat(job.runs()).isZero();
    assertThat(job.status()).isEqualTo(SyncStatus.SUCCESS);
  }

  @Test
  void unknownSourceOrJobIsRejected() {
    assertThatThrownBy(() -> trigger().run("nope", null))
        .isInstanceOf(UnknownJobException.class)
        .hasMessageContaining("nope");
    assertThatThrownBy(() -> trigger().run("argo", "nope"))
        .isInstanceOf(UnknownJobException.class)
        .hasMessageContaining("argo/nope");
  }

  @Test
  void aSecondTriggerOfTheSameSourceWhileOneRunsIsRefused() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var trigger =
        new CollectionTrigger(
            List.of(
                CollectionJob.of(
                    "argo",
                    "sites",
                    () -> {
                      started.countDown();
                      try {
                        release.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return CollectionResult.none();
                    }),
                job("k8s", "workloads", SyncStatus.SUCCESS)));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> first = pool.submit(() -> trigger.run("argo", null));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> trigger.run("argo", null)).isInstanceOf(TriggerBusyException.class);
      assertThat(trigger.run("k8s", null).jobs()).hasSize(1); // other sources are not blocked

      release.countDown();
      first.get(5, TimeUnit.SECONDS);
      assertThat(trigger.run("argo", null).jobs()).hasSize(1); // free again afterwards
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
