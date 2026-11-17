package io.cmdb.collector.leader.infrastructure;

import io.cmdb.collector.leader.domain.LeaderGate;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Leader election with a Lease on the corporate-network K8s (unrelated to the clusters being
 * watched). One background thread per lockName; after losing leadership it stands for election
 * again. Needs RBAC: get / create / update on leases (coordination.k8s.io) in that namespace.
 */
@Component
@ConditionalOnProperty(name = "collector.leader-election.enabled", havingValue = "true")
class LeaseLeaderGate implements LeaderGate {

  private static final Logger log = LoggerFactory.getLogger(LeaseLeaderGate.class);

  private final LeaderProperties props;
  private final String identity;
  private final KubernetesClient client = new KubernetesClientBuilder().build();
  private final Map<String, AtomicBoolean> leaders = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<?>> running = new ConcurrentHashMap<>();
  private volatile boolean closed;

  LeaseLeaderGate(LeaderProperties props) {
    this.props = props;
    this.identity =
        props.identity() != null && !props.identity().isBlank()
            ? props.identity()
            : System.getenv().getOrDefault("HOSTNAME", UUID.randomUUID().toString());
  }

  @Override
  public boolean isLeader(String lockName) {
    return leaders.computeIfAbsent(lockName, this::startElection).get();
  }

  private AtomicBoolean startElection(String lockName) {
    AtomicBoolean flag = new AtomicBoolean(false);
    Thread.ofPlatform()
        .name("leader-" + lockName)
        .daemon()
        .start(() -> electionLoop(lockName, flag));
    return flag;
  }

  private void electionLoop(String lockName, AtomicBoolean flag) {
    while (!closed) {
      try {
        CompletableFuture<?> election =
            client
                .leaderElector()
                .withConfig(
                    new LeaderElectionConfigBuilder()
                        .withName(lockName)
                        .withLeaseDuration(props.leaseDuration())
                        .withRenewDeadline(props.renewDeadline())
                        .withRetryPeriod(props.retryPeriod())
                        .withReleaseOnCancel(true)
                        .withLock(new LeaseLock(props.namespace(), lockName, identity))
                        .withLeaderCallbacks(
                            new LeaderCallbacks(
                                () -> {
                                  flag.set(true);
                                  log.info("[{}] {} became leader", lockName, identity);
                                },
                                () -> {
                                  flag.set(false);
                                  log.warn("[{}] {} lost leadership", lockName, identity);
                                },
                                leader -> log.info("[{}] current leader: {}", lockName, leader)))
                        .build())
                .build()
                .start();
        running.put(lockName, election);
        election.get(); // returns only when leadership is lost or cancelled
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        log.warn("[{}] leader election error, retrying", lockName, e);
      } finally {
        flag.set(false);
      }
      try {
        Thread.sleep(props.retryPeriod().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @PreDestroy
  void close() {
    closed = true;
    running
        .values()
        .forEach(
            f ->
                f.cancel(
                    true)); // releaseOnCancel: gives up the Lease so another instance can take over
    // immediately
    client.close();
  }
}
