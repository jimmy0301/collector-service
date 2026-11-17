package io.cmdb.collector.leader.infrastructure;

import io.cmdb.collector.leader.domain.LeaderGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single instance / local development: always the leader. For multiple replicas in production
 * enable collector.leader-election.enabled.
 */
@Component
@ConditionalOnProperty(
    name = "collector.leader-election.enabled",
    havingValue = "false",
    matchIfMissing = true)
class AlwaysLeaderGate implements LeaderGate {

  @Override
  public boolean isLeader(String lockName) {
    return true;
  }
}
