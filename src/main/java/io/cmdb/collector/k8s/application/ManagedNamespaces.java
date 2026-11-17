package io.cmdb.collector.k8s.application;

import io.cmdb.collector.argo.ArgoScopeObserved;
import io.cmdb.collector.argo.Destination;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The namespaces to query, maintained by the {@code k8s} context itself and updated by the {@link
 * ArgoScopeObserved} event published by {@code argo}. Held in memory only (a synchronous
 * {@code @EventListener}, not persisted by Modulith); the first Argo poll after a restart rebuilds
 * it.
 */
@Component
class ManagedNamespaces {

  private final Map<String, List<Destination>> bySite = new ConcurrentHashMap<>();

  @EventListener
  void on(ArgoScopeObserved event) {
    bySite.put(event.site(), List.copyOf(event.destinations()));
  }

  /**
   * Matches a cluster by the destination's server or name and returns the namespaces to query for
   * that cluster.
   */
  Set<String> namespacesFor(String site, String clusterName, String clusterServer) {
    Set<String> result = new TreeSet<>();
    for (Destination d : bySite.getOrDefault(site, List.of())) {
      boolean sameCluster =
          (d.server() != null
                  && !d.server().isBlank()
                  && normalize(d.server()).equals(normalize(clusterServer)))
              || (d.name() != null && !d.name().isBlank() && d.name().equals(clusterName));
      if (sameCluster && d.namespace() != null && !d.namespace().isBlank()) {
        result.add(d.namespace());
      }
    }
    return result;
  }

  private static String normalize(String server) {
    if (server == null) {
      return "";
    }
    String s = server.trim().toLowerCase();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
