package io.cmdb.collector.k8s.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.argo.ArgoScopeObserved;
import io.cmdb.collector.argo.Destination;
import io.cmdb.collector.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A real Spring context (including Modulith's event multicaster): the event published by argo does
 * update the k8s scope.
 */
class ManagedNamespacesWiringIT extends PostgresIntegrationTest {

  @Autowired ApplicationEventPublisher publisher;
  @Autowired ManagedNamespaces namespaces;

  @Test
  void argoScopeEventsReachTheK8sContext() {
    String site = "S-" + UUID.randomUUID();

    publisher.publishEvent(
        new ArgoScopeObserved(site, List.of(new Destination("https://k:6443/", null, "shop"))));

    assertThat(namespaces.namespacesFor(site, "any", "https://K:6443")).containsExactly("shop");
  }
}
