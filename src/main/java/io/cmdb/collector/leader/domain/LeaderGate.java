package io.cmdb.collector.leader.domain;

/** Among several Collector instances, only one may pull a given source (PLAN.md 3.1). */
public interface LeaderGate {

  /**
   * Whether this instance currently holds the leadership of {@code lockName}; if not, skip this
   * round.
   */
  boolean isLeader(String lockName);
}
