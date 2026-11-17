package io.cmdb.collector.ado.application;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.ado.domain.AdoHub;
import io.cmdb.collector.rawevent.application.RawEventRecorder;
import io.cmdb.collector.support.Fakes.InMemoryRuns;
import io.cmdb.collector.support.Fakes.InMemorySink;
import io.cmdb.collector.support.Fixtures;
import io.cmdb.collector.syncrun.application.SyncRunTracker;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdoCollectorTest {

  static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");

  static class FakeHub implements AdoHub {
    List<JsonNode> deployments = new ArrayList<>();
    final List<String> deploymentRequests = new ArrayList<>();
    final List<String> releaseRequests = new ArrayList<>();
    Set<String> failingProjects = Set.of();
    Set<String> failingReleases = Set.of();

    @Override
    public List<JsonNode> deploymentsModifiedSince(String project, Instant since) {
      deploymentRequests.add(project + "@" + since);
      if (failingProjects.contains(project)) {
        throw new IllegalStateException("401 unauthorized");
      }
      return deployments;
    }

    @Override
    public JsonNode release(String project, String releaseId) {
      releaseRequests.add(project + "/" + releaseId);
      if (failingReleases.contains(releaseId)) {
        throw new IllegalStateException("500 boom");
      }
      return json(Fixtures.load("ado/release-310.json"));
    }
  }

  final FakeHub hub = new FakeHub();
  final InMemorySink sink = new InMemorySink();
  final InMemoryRuns runs = new InMemoryRuns();
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  AdoCollector collector(String organization, String... projects) {
    return new AdoCollector(
        hub,
        new RawEventRecorder(sink, event -> {}, new SimpleMeterRegistry(), 1000),
        new SyncRunTracker(runs, clock),
        clock,
        List.of(projects),
        organization,
        Duration.ofDays(7),
        Duration.ofMinutes(5));
  }

  void fixtureDeployments() {
    hub.deployments = new ArrayList<>();
    json(Fixtures.load("ado/deployments.json")).path("value").forEach(hub.deployments::add);
  }

  @Test
  void recordsEachDeploymentAndItsReleaseWithAReleaseOrderingKey() {
    fixtureDeployments();

    collector("org", "Shop").collectReleases();

    var deployment = sink.ofType("RELEASE_DEPLOYMENT");
    assertThat(deployment).hasSize(2);
    assertThat(deployment.get(0).provider()).isEqualTo("ADO");
    assertThat(deployment.get(0).externalEventId())
        .isEqualTo("ado:Shop:deployment:9001:2026-11-14T01:05:00.000Z");
    assertThat(deployment.get(0).orderingKey()).isEqualTo("ado:Shop:release:310");
    assertThat(deployment.get(0).payload().path("project").asString()).isEqualTo("Shop");
    assertThat(deployment.get(0).payload().at("/resource/releaseEnvironment/name").asString())
        .isEqualTo("uat");

    var release = sink.ofType("RELEASE");
    assertThat(release).hasSize(2); // one fetch per release (the fake returns release 310 twice)
    assertThat(hub.releaseRequests).containsExactly("Shop/310", "Shop/311");
    assertThat(release.get(0).orderingKey()).isEqualTo("ado:Shop:release:310");
    assertThat(release.get(0).payload().toString()).doesNotContain("hunter2");
  }

  @Test
  void theFirstPollLooksBackByTheConfiguredLookback() {
    collector("org", "Shop").collectReleases();

    assertThat(hub.deploymentRequests).containsExactly("Shop@2026-11-07T03:00:00Z");
  }

  @Test
  void afterASuccessfulRoundTheWatermarkMovesToTheNewestModificationMinusTheOverlap() {
    fixtureDeployments();
    var collector = collector("org", "Shop");

    collector.collectReleases();
    collector.collectReleases();

    // newest lastModifiedOn is 02:01:30, minus 5 minutes of overlap
    assertThat(hub.deploymentRequests.get(1)).isEqualTo("Shop@2026-11-14T01:56:30Z");
  }

  @Test
  void aRepeatedRoundAddsNoEventsAndDoesNotFetchTheReleasesAgain() {
    fixtureDeployments();
    var collector = collector("org", "Shop");
    collector.collectReleases();
    int events = sink.events.size();

    collector.collectReleases(); // the overlap returns the same deployments

    assertThat(sink.events).hasSize(events);
    assertThat(hub.releaseRequests).hasSize(2);
  }

  @Test
  void aReleaseIsFetchedAgainWhenOneOfItsDeploymentsChanged() {
    fixtureDeployments();
    var collector = collector("org", "Shop");
    collector.collectReleases();

    ((tools.jackson.databind.node.ObjectNode) hub.deployments.get(0))
        .put("lastModifiedOn", "2026-11-14T02:30:00.000Z");
    collector.collectReleases();

    assertThat(hub.releaseRequests).containsExactly("Shop/310", "Shop/311", "Shop/310");
    assertThat(sink.ofType("RELEASE_DEPLOYMENT")).hasSize(3);
  }

  @Test
  void aFailedReleaseFetchMakesTheRoundPartialAndKeepsTheWatermarkSoItIsRetried() {
    fixtureDeployments();
    hub.failingReleases = Set.of("311");
    var collector = collector("org", "Shop");

    collector.collectReleases();

    assertThat(runs.last().status()).isEqualTo(SyncStatus.PARTIAL);
    assertThat(sink.ofType("RELEASE_DEPLOYMENT")).hasSize(2); // the deployments are still landed
    collector.collectReleases();
    assertThat(hub.deploymentRequests.get(1)).isEqualTo("Shop@2026-11-07T03:00:00Z");
    hub.failingReleases = Set.of();
    collector.collectReleases();
    assertThat(hub.releaseRequests).contains("Shop/311");
  }

  @Test
  void aDeploymentWithoutAReleaseIdIsReportedAndSkipped() {
    hub.deployments = List.of(json("{\"id\":1,\"lastModifiedOn\":\"2026-11-14T01:00:00Z\"}"));

    collector("org", "Shop").collectReleases();

    assertThat(sink.events).isEmpty();
    assertThat(runs.last().status()).isEqualTo(SyncStatus.PARTIAL);
  }

  @Test
  void aFailingProjectDoesNotStopTheOthersAndIsRecordedAsFailed() {
    fixtureDeployments();
    hub.failingProjects = Set.of("Broken");

    var result = collector("org", "Broken", "Shop").collectReleases();

    assertThat(result.runs()).isEqualTo(2);
    assertThat(result.status()).isEqualTo(SyncStatus.FAILED); // the worst of the two runs
    assertThat(result.errors()).singleElement().asString().startsWith("ADO:Broken: ");
    assertThat(runs.runs).hasSize(2);
    assertThat(runs.runs.get(0).status()).isEqualTo(SyncStatus.FAILED);
    assertThat(runs.runs.get(1).status()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(sink.ofType("RELEASE_DEPLOYMENT")).hasSize(2);
  }

  @Test
  void nothingIsPolledWithoutAnOrganizationOrProjects() {
    var results =
        List.of(
            collector("", "Shop").collectReleases(),
            collector("org").collectReleases(),
            collector("org", "").collectReleases());

    assertThat(results).allSatisfy(r -> assertThat(r.runs()).isZero());
    assertThat(hub.deploymentRequests).isEmpty();
    assertThat(runs.runs).isEmpty();
  }
}
