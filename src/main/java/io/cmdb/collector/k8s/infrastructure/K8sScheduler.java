package io.cmdb.collector.k8s.infrastructure;

import io.cmdb.collector.k8s.application.K8sCollector;
import io.cmdb.collector.leader.domain.LeaderGate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class K8sScheduler {

  static final String LOCK = "cmdb-collector-k8s";

  private final K8sCollector collector;
  private final LeaderGate leader;

  K8sScheduler(K8sCollector collector, LeaderGate leader) {
    this.collector = collector;
    this.leader = leader;
  }

  // Start a little after startup so the first Argo round can establish the namespace scope
  @Scheduled(
      fixedDelayString = "${collector.k8s.poll-interval:60s}",
      initialDelayString = "${collector.k8s.initial-delay:20s}")
  void pollWorkloads() {
    if (leader.isLeader(LOCK)) {
      collector.collectWorkloads();
    }
  }

  @Scheduled(
      fixedDelayString = "${collector.k8s.event-interval:30s}",
      initialDelayString = "${collector.k8s.initial-delay:20s}")
  void pollEvents() {
    if (leader.isLeader(LOCK)) {
      collector.collectEvents();
    }
  }

  @Scheduled(
      fixedDelayString = "${collector.k8s.cluster-interval:1h}",
      initialDelayString = "${collector.k8s.initial-delay:20s}")
  void pollSites() {
    if (leader.isLeader(LOCK)) {
      collector.collectSites();
    }
  }

  @Scheduled(
      fixedDelayString = "${collector.k8s.cluster-interval:1h}",
      initialDelayString = "${collector.k8s.initial-delay:20s}")
  void pollClusters() {
    if (leader.isLeader(LOCK)) {
      collector.collectClusters();
    }
  }
}
