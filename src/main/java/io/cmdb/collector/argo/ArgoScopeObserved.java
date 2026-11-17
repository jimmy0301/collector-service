package io.cmdb.collector.argo;

import java.util.List;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * The deployment destinations seen by one site's Argo poll (excluding the app-of-apps parents).
 *
 * <p>An event published by the {@code argo} context: the {@code k8s} context uses it to decide
 * which clusters and namespaces to query (PLAN.md 9.3: K8s queries only what Argo manages). The two
 * contexts do not share state directly.
 */
@DomainEvent
public record ArgoScopeObserved(String site, List<Destination> destinations) {}
