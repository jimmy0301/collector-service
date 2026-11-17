package io.cmdb.collector.k8s.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.cmdb.collector.k8s.domain.Cluster;
import io.cmdb.collector.k8s.domain.ResourceKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * The whole K8s hub client (Bearer, gzip, pagination, 410 Gone) against a WireMock server, without
 * a mock RestClient factory.
 */
class HttpK8sHubTest {

  static final String PODS = "/sites/PLANT-A/clusters/cid-1/api/v1/namespaces/shop/pods";

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  HttpK8sHub hub =
      new HttpK8sHub(
          RestClient.builder(),
          new K8sProperties(
              wm.baseUrl(),
              "test-token",
              List.of(),
              2,
              Duration.ofSeconds(2),
              Duration.ofSeconds(5)));

  static ResponseDefinitionBuilder page(String items, String next) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody("{\"metadata\":{\"continue\":\"%s\"},\"items\":[%s]}".formatted(next, items));
  }

  static byte[] gzip(String text) {
    try {
      var out = new java.io.ByteArrayOutputStream();
      try (var gz = new java.util.zip.GZIPOutputStream(out)) {
        gz.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      return out.toByteArray();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  List<String> collect(String fieldSelector) {
    List<String> names = new ArrayList<>();
    hub.list(
        "PLANT-A",
        "cid-1",
        ResourceKind.POD,
        "shop",
        fieldSelector,
        item -> names.add(item.path("name").asString()));
    return names;
  }

  @Test
  void followsContinueTokensAndSendsBearerAndLimit() {
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", equalTo("tok=="))
            .willReturn(page("{\"name\":\"c\"}", "")));
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", notMatching(".+"))
            .willReturn(page("{\"name\":\"a\"},{\"name\":\"b\"}", "tok==")));

    assertThat(collect(null)).containsExactly("a", "b", "c");

    wm.verify(
        2,
        getRequestedFor(urlPathEqualTo(PODS))
            .withHeader("Authorization", equalTo("Bearer test-token")));
    wm.verify(
        getRequestedFor(urlPathEqualTo(PODS))
            .withQueryParam("limit", equalTo("2"))
            .withQueryParam("continue", notMatching(".+")));
  }

  @Test
  void restartsFromScratchOnceWhenContinueTokenExpires() {
    // first list ends with a token that expires; the restarted list returns a fresh token
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", equalTo("old"))
            .willReturn(aResponse().withStatus(410).withBody("{\"reason\":\"Expired\"}")));
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", equalTo("new"))
            .willReturn(page("{\"name\":\"c\"}", "")));
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", notMatching(".+"))
            .inScenario("restart")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(page("{\"name\":\"a\"}", "old"))
            .willSetStateTo("restarted"));
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", notMatching(".+"))
            .inScenario("restart")
            .whenScenarioStateIs("restarted")
            .willReturn(page("{\"name\":\"a\"}", "new")));

    assertThat(collect(null)).containsExactly("a", "a", "c");
  }

  @Test
  void givesUpWhenStillGoneAfterRestart() {
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", matching(".+"))
            .willReturn(aResponse().withStatus(410).withBody("{}")));
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .withQueryParam("continue", notMatching(".+"))
            .willReturn(page("{\"name\":\"a\"}", "tok")));

    assertThatThrownBy(() -> collect(null)).hasMessageContaining("410");
  }

  @Test
  void passesFieldSelectorAndHandlesGzip() {
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Content-Encoding", "gzip")
                    .withGzipDisabled(true) // the body below is already gzipped
                    .withBody(gzip("{\"metadata\":{},\"items\":[{\"name\":\"a\"}]}"))));

    assertThat(collect("type=Warning")).containsExactly("a");

    wm.verify(
        getRequestedFor(urlPathEqualTo(PODS))
            .withQueryParam("fieldSelector", equalTo("type=Warning")));
  }

  @Test
  void upstreamErrorsPropagateWithStatus() {
    wm.stubFor(
        get(urlPathEqualTo(PODS))
            .willReturn(
                aResponse()
                    .withStatus(502)
                    .withBody("{\"site\":\"PLANT-A\",\"error\":\"unreachable\"}")));

    assertThatThrownBy(() -> collect(null))
        .isInstanceOf(HttpServerErrorException.class)
        .hasMessageContaining("502");
  }

  @Test
  void rejectsInvalidNamespaceBeforeAnyRequest() {
    assertThatThrownBy(() -> hub.list("S", "c", ResourceKind.POD, "../secrets", null, item -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(wm.getAllServeEvents()).isEmpty();
  }

  @Test
  void parsesSitesAndClusters() {
    wm.stubFor(
        get(urlPathEqualTo("/sites/PLANT-A/clusters"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "[{\"id\":\"cid-1\",\"name\":\"plant-a\",\"server\":\"https://k:6443\"}]")));
    wm.stubFor(
        get(urlPathEqualTo("/sites"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"code\":\"PLANT-A\"},{\"code\":\"PLANT-B\"}]")));

    assertThat(hub.sites()).containsExactly("PLANT-A", "PLANT-B");
    assertThat(hub.clusters("PLANT-A"))
        .containsExactly(new Cluster("cid-1", "plant-a", "https://k:6443"));
  }
}
