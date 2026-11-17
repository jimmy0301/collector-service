package io.cmdb.collector.k8s.infrastructure;

import io.cmdb.collector.http.HubRestClients;
import io.cmdb.collector.k8s.domain.Cluster;
import io.cmdb.collector.k8s.domain.K8sHub;
import io.cmdb.collector.k8s.domain.ResourceKind;
import io.cmdb.collector.k8s.domain.Site;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
class HttpK8sHub implements K8sHub {

  private final RestClient client;
  private final K8sProperties props;

  HttpK8sHub(RestClient.Builder builder, K8sProperties props) {
    this.props = props;
    this.client =
        HubRestClients.create(
            builder, props.baseUrl(), props.token(), props.connectTimeout(), props.readTimeout());
  }

  @Override
  public List<String> sites() {
    return siteDetails().stream().map(Site::code).toList();
  }

  @Override
  public List<Site> siteDetails() {
    if (!props.sites().isEmpty()) {
      return props.sites().stream().map(code -> new Site(code, null, null)).toList();
    }
    JsonNode body = client.get().uri("/sites").retrieve().body(JsonNode.class);
    List<Site> sites = new ArrayList<>();
    if (body != null) {
      body.forEach(
          s ->
              sites.add(
                  new Site(
                      s.path("code").asString(),
                      s.path("name").asString(null),
                      s.path("region").asString(null))));
    }
    return sites;
  }

  @Override
  public List<Cluster> clusters(String site) {
    JsonNode body =
        client.get().uri("/sites/{site}/clusters", site).retrieve().body(JsonNode.class);
    List<Cluster> clusters = new ArrayList<>();
    if (body != null) {
      body.forEach(
          c ->
              clusters.add(
                  new Cluster(
                      c.path("id").asString(),
                      c.path("name").asString(),
                      c.path("server").asString(""))));
    }
    return clusters;
  }

  @Override
  public void list(
      String site,
      String clusterId,
      ResourceKind kind,
      String namespace,
      String fieldSelector,
      Consumer<JsonNode> consumer) {
    String path = "/sites/{site}/clusters/{cluster}" + kind.namespacedPath(namespace);
    String token = null;
    boolean restarted = false;
    while (true) {
      JsonNode page;
      try {
        page = fetchPage(path, site, clusterId, fieldSelector, token);
      } catch (HttpClientErrorException.Gone gone) {
        // The continue token expired (410 Gone): list again from the start; items already handled
        // are deduplicated by event ID
        if (token != null && !restarted) {
          restarted = true;
          token = null;
          continue;
        }
        throw gone;
      }
      if (page == null) {
        return;
      }
      page.path("items").forEach(consumer);
      String next = page.path("metadata").path("continue").asString("");
      if (next.isEmpty()) {
        return;
      }
      token = next;
    }
  }

  private JsonNode fetchPage(
      String path, String site, String clusterId, String fieldSelector, String token) {
    Map<String, Object> vars = new HashMap<>();
    vars.put("site", site);
    vars.put("cluster", clusterId);
    vars.put("limit", props.pageSize());
    vars.put("continue", token);
    vars.put("fieldSelector", fieldSelector);
    return client
        .get()
        .uri(
            uri -> {
              uri.path(path).queryParam("limit", "{limit}");
              if (token != null) {
                uri.queryParam("continue", "{continue}");
              }
              if (fieldSelector != null) {
                uri.queryParam("fieldSelector", "{fieldSelector}");
              }
              return uri.build(vars);
            })
        .retrieve()
        .body(JsonNode.class);
  }
}
