package io.cmdb.collector.argo.infrastructure;

import io.cmdb.collector.argo.application.ArgoCollector;
import io.cmdb.collector.leader.domain.LeaderGate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ArgoScheduler {

  static final String LOCK = "cmdb-collector-argo";

  private final ArgoCollector collector;
  private final LeaderGate leader;

  ArgoScheduler(ArgoCollector collector, LeaderGate leader) {
    this.collector = collector;
    this.leader = leader;
  }

  @Scheduled(
      fixedDelayString = "${collector.argo.poll-interval:30s}",
      initialDelayString = "${collector.argo.initial-delay:5s}")
  void pollApplications() {
    if (leader.isLeader(LOCK)) {
      collector.collectApplications();
    }
  }

  @Scheduled(
      fixedDelayString = "${collector.argo.cluster-interval:1h}",
      initialDelayString = "${collector.argo.initial-delay:5s}")
  void pollSites() {
    if (leader.isLeader(LOCK)) {
      collector.collectSites();
    }
  }

  @Scheduled(
      fixedDelayString = "${collector.argo.cluster-interval:1h}",
      initialDelayString = "${collector.argo.cluster-initial-delay:60s}")
  void pollClusters() {
    if (leader.isLeader(LOCK)) {
      collector.collectClusters();
    }
  }
}
