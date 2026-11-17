package io.cmdb.collector.k8s.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.argo.ArgoScopeObserved;
import io.cmdb.collector.argo.Destination;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedNamespacesTest {

  ManagedNamespaces namespaces = new ManagedNamespaces();

  @Test
  void matchesClustersByServerIgnoringCaseAndTrailingSlash() {
    namespaces.on(
        new ArgoScopeObserved(
            "PLANT-A",
            List.of(
                new Destination("https://K8S.plant-a:6443/", null, "shop"),
                new Destination("https://k8s.plant-a:6443", null, "billing"),
                new Destination("https://other:6443", null, "elsewhere"))));

    assertThat(namespaces.namespacesFor("PLANT-A", "plant-a", "https://k8s.plant-a:6443/"))
        .containsExactly("billing", "shop");
  }

  @Test
  void matchesClustersByDestinationName() {
    namespaces.on(
        new ArgoScopeObserved("PLANT-A", List.of(new Destination(null, "plant-a", "shop"))));

    assertThat(namespaces.namespacesFor("PLANT-A", "plant-a", "https://x")).containsExactly("shop");
    assertThat(namespaces.namespacesFor("PLANT-A", "plant-b", "https://x")).isEmpty();
  }

  @Test
  void ignoresDestinationsWithoutNamespace() {
    namespaces.on(
        new ArgoScopeObserved(
            "PLANT-A",
            List.of(
                new Destination("https://k", null, null),
                new Destination("https://k", null, " "))));

    assertThat(namespaces.namespacesFor("PLANT-A", "c", "https://k")).isEmpty();
  }

  @Test
  void sitesAreIsolatedAndEachEventReplacesThePreviousScopeOfItsSite() {
    namespaces.on(new ArgoScopeObserved("A", List.of(new Destination("https://k", null, "one"))));
    namespaces.on(new ArgoScopeObserved("B", List.of(new Destination("https://k", null, "two"))));
    assertThat(namespaces.namespacesFor("A", "c", "https://k")).containsExactly("one");
    assertThat(namespaces.namespacesFor("B", "c", "https://k")).containsExactly("two");

    namespaces.on(new ArgoScopeObserved("A", List.of(new Destination("https://k", null, "three"))));
    assertThat(namespaces.namespacesFor("A", "c", "https://k")).containsExactly("three");
  }
}
