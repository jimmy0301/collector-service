package io.cmdb.collector.ado.infrastructure;

import io.cmdb.collector.ado.application.AdoCollector;
import io.cmdb.collector.leader.domain.LeaderGate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AdoScheduler {

  static final String LOCK = "cmdb-collector-ado";

  private final AdoCollector collector;
  private final LeaderGate leader;

  AdoScheduler(AdoCollector collector, LeaderGate leader) {
    this.collector = collector;
    this.leader = leader;
  }

  @Scheduled(
      fixedDelayString = "${collector.ado.poll-interval:60s}",
      initialDelayString = "${collector.ado.initial-delay:15s}")
  void pollReleases() {
    if (leader.isLeader(LOCK)) {
      collector.collectReleases();
    }
  }
}
