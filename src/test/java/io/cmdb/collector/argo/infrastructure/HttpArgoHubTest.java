package io.cmdb.collector.argo.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cmdb.collector.argo.domain.Site;
import io.cmdb.collector.support.Fixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * The Argo hub client against a WireMock server serving fixtures written after
 * INTEGRATION_API_SPEC.md chapter 4.
 */
class HttpArgoHubTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  HttpArgoHub hub(List<String> sites) {
    return new HttpArgoHub(
        RestClient.builder(),
        new ArgoProperties(
            wm.baseUrl(), "test-token", sites, Duration.ofSeconds(2), Duration.ofSeconds(5)));
  }

  @Test
  void parsesSitesWithOptionalNameAndRegionAndSendsBearer() {
    wm.stubFor(get(urlEqualTo("/sites")).willReturn(json(Fixtures.load("argo/sites.json"))));

    assertThat(hub(List.of()).siteDetails())
        .containsExactly(new Site("PLANT-A", "A Plant", "north"), new Site("PLANT-B", null, null));

    wm.verify(
        getRequestedFor(urlEqualTo("/sites"))
            .withHeader("Authorization", equalTo("Bearer test-token")));
  }

  @Test
  void configuredSitesAreUsedWithoutCallingTheHub() {
    assertThat(hub(List.of("PLANT-A")).sites()).containsExactly("PLANT-A");

    assertThat(wm.getAllServeEvents()).isEmpty();
  }

  @Test
  void fetchesApplicationsAndClustersPerSite() {
    wm.stubFor(
        get(urlEqualTo("/sites/PLANT-A/argocd/api/v1/applications"))
            .willReturn(json(Fixtures.load("argo/applications.json"))));
    wm.stubFor(
        get(urlEqualTo("/sites/PLANT-A/argocd/api/v1/clusters"))
            .willReturn(json(Fixtures.load("argo/clusters.json"))));

    var apps = hub(List.of()).applications("PLANT-A");
    var clusters = hub(List.of()).clusters("PLANT-A");

    assertThat(apps.at("/items/0/metadata/uid").asString()).isEqualTo("app-uid-1");
    assertThat(apps.at("/items/0/spec/destination/namespace").asString()).isEqualTo("shop");
    assertThat(clusters.at("/items/1/server").asString()).isEqualTo("https://k8s.plant-a:6443");
  }

  @Test
  void resourceTreePassesAppNamespaceAndProjectOnlyWhenGiven() {
    wm.stubFor(
        get(urlPathEqualTo("/sites/PLANT-A/argocd/api/v1/applications/shop/resource-tree"))
            .willReturn(json(Fixtures.load("argo/resource-tree.json"))));

    var tree = hub(List.of()).resourceTree("PLANT-A", "shop", "argocd", "default");
    hub(List.of()).resourceTree("PLANT-A", "shop", null, null);

    assertThat(tree.at("/nodes/0/uid").asString()).isEqualTo("dep-1");
    wm.verify(
        getRequestedFor(
                urlPathEqualTo("/sites/PLANT-A/argocd/api/v1/applications/shop/resource-tree"))
            .withQueryParam("appNamespace", equalTo("argocd"))
            .withQueryParam("project", equalTo("default")));
    var requests = wm.getAllServeEvents();
    assertThat(requests.getFirst().getRequest().getUrl()).doesNotContain("appNamespace");
  }

  @Test
  void upstreamErrorsPropagateWithStatus() {
    wm.stubFor(
        get(urlEqualTo("/sites/PLANT-A/argocd/api/v1/applications"))
            .willReturn(aResponse().withStatus(502).withBody("{\"error\":\"unreachable\"}")));

    assertThatThrownBy(() -> hub(List.of()).applications("PLANT-A"))
        .isInstanceOf(HttpServerErrorException.class)
        .hasMessageContaining("502");
  }

  static ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }
}
