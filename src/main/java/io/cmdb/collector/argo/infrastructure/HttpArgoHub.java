package io.cmdb.collector.argo.infrastructure;

import io.cmdb.collector.argo.domain.ArgoHub;
import io.cmdb.collector.argo.domain.Site;
import io.cmdb.collector.http.HubRestClients;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
class HttpArgoHub implements ArgoHub {

  private final RestClient client;
  private final ArgoProperties props;

  HttpArgoHub(RestClient.Builder builder, ArgoProperties props) {
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
    List<Site> sites = new ArrayList<>();
    get("/sites")
        .forEach(
            s ->
                sites.add(
                    new Site(
                        s.path("code").asString(),
                        s.path("name").asString(null),
                        s.path("region").asString(null))));
    return sites;
  }

  @Override
  public JsonNode applications(String site) {
    return get("/sites/{site}/argocd/api/v1/applications", site);
  }

  @Override
  public JsonNode resourceTree(String site, String appName, String appNamespace, String project) {
    return client
        .get()
        .uri(
            uri -> {
              uri.path("/sites/{site}/argocd/api/v1/applications/{name}/resource-tree");
              if (appNamespace != null) {
                uri.queryParam("appNamespace", appNamespace);
              }
              if (project != null) {
                uri.queryParam("project", project);
              }
              return uri.build(site, appName);
            })
        .retrieve()
        .body(JsonNode.class);
  }

  @Override
  public JsonNode clusters(String site) {
    return get("/sites/{site}/argocd/api/v1/clusters", site);
  }

  private JsonNode get(String path, Object... vars) {
    JsonNode body = client.get().uri(path, vars).retrieve().body(JsonNode.class);
    return body == null ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode() : body;
  }
}
