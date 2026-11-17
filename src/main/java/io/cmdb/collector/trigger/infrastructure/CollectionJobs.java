package io.cmdb.collector.trigger.infrastructure;

import io.cmdb.collector.ado.application.AdoCollector;
import io.cmdb.collector.argo.application.ArgoCollector;
import io.cmdb.collector.k8s.application.K8sCollector;
import io.cmdb.collector.trigger.domain.CollectionJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The collect operations that can be triggered. The order is the order "all" runs them: Argo before
 * Kubernetes (Kubernetes only reads namespaces that Argo Applications point at); within a source,
 * sites and clusters first.
 */
@Configuration
class CollectionJobs {

  @Bean
  @Order(1)
  CollectionJob argoSites(ArgoCollector argo) {
    return CollectionJob.of("argo", "sites", argo::collectSites);
  }

  @Bean
  @Order(2)
  CollectionJob argoClusters(ArgoCollector argo) {
    return CollectionJob.of("argo", "clusters", argo::collectClusters);
  }

  @Bean
  @Order(3)
  CollectionJob argoApplications(ArgoCollector argo) {
    return CollectionJob.of("argo", "applications", argo::collectApplications);
  }

  @Bean
  @Order(4)
  CollectionJob k8sSites(K8sCollector k8s) {
    return CollectionJob.of("k8s", "sites", k8s::collectSites);
  }

  @Bean
  @Order(5)
  CollectionJob k8sClusters(K8sCollector k8s) {
    return CollectionJob.of("k8s", "clusters", k8s::collectClusters);
  }

  @Bean
  @Order(6)
  CollectionJob k8sWorkloads(K8sCollector k8s) {
    return CollectionJob.of("k8s", "workloads", k8s::collectWorkloads);
  }

  @Bean
  @Order(7)
  CollectionJob k8sEvents(K8sCollector k8s) {
    return CollectionJob.of("k8s", "events", k8s::collectEvents);
  }

  @Bean
  @Order(8)
  CollectionJob adoReleases(AdoCollector ado) {
    return CollectionJob.of("ado", "releases", ado::collectReleases);
  }
}
