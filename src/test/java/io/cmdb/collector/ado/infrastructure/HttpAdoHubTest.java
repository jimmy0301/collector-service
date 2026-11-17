package io.cmdb.collector.ado.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cmdb.collector.support.Fixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The Azure DevOps Release client against WireMock, with fixtures written after Release REST 7.1.
 */
class HttpAdoHubTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  HttpAdoHub hub() {
    return new HttpAdoHub(
        RestClient.builder(),
        new AdoProperties(
            wm.baseUrl(),
            "org",
            List.of("Shop"),
            "my-pat",
            2,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5)));
  }

  @Test
  void sendsThePatAsBasicAuthenticationWithAnEmptyUserName() {
    wm.stubFor(
        get(urlPathEqualTo("/org/Shop/_apis/release/deployments"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"value\":[]}")));

    hub().deploymentsModifiedSince("Shop", Instant.parse("2026-11-14T00:00:00Z"));

    String expected =
        "Basic " + Base64.getEncoder().encodeToString(":my-pat".getBytes(StandardCharsets.UTF_8));
    wm.verify(
        getRequestedFor(urlPathEqualTo("/org/Shop/_apis/release/deployments"))
            .withHeader("Authorization", equalTo(expected)));
  }

  @Test
  void asksForAscendingDeploymentsModifiedSinceWithTheApiVersionAndPageSize() {
    wm.stubFor(
        get(urlPathEqualTo("/org/Shop/_apis/release/deployments"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(Fixtures.load("ado/deployments.json"))));

    var deployments = hub().deploymentsModifiedSince("Shop", Instant.parse("2026-11-14T00:00:00Z"));

    assertThat(deployments).hasSize(2);
    assertThat(deployments.get(0).path("id").asInt()).isEqualTo(9001);
    wm.verify(
        getRequestedFor(urlPathEqualTo("/org/Shop/_apis/release/deployments"))
            .withQueryParam("api-version", equalTo("7.1"))
            .withQueryParam("minModifiedTime", equalTo("2026-11-14T00:00:00Z"))
            .withQueryParam("queryOrder", equalTo("ascending"))
            .withQueryParam("$top", equalTo("2")));
  }

  @Test
  void followsTheContinuationTokenUntilThereIsNone() {
    wm.stubFor(
        get(urlPathEqualTo("/org/Shop/_apis/release/deployments"))
            .withQueryParam(
                "continuationToken", com.github.tomakehurst.wiremock.client.WireMock.absent())
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withHeader("x-ms-continuationtoken", "tok 2")
                    .withBody("{\"value\":[{\"id\":1}]}")));
    wm.stubFor(
        get(urlPathEqualTo("/org/Shop/_apis/release/deployments"))
            .withQueryParam("continuationToken", equalTo("tok 2"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"value\":[{\"id\":2}]}")));

    var deployments = hub().deploymentsModifiedSince("Shop", Instant.parse("2026-11-14T00:00:00Z"));

    assertThat(deployments).extracting(d -> d.path("id").asInt()).containsExactly(1, 2);
  }

  @Test
  void fetchesAReleaseById() {
    wm.stubFor(
        get(urlPathEqualTo("/org/Shop/_apis/release/releases/310"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(Fixtures.load("ado/release-310.json"))));

    var release = hub().release("Shop", "310");

    assertThat(release.path("name").asString()).isEqualTo("Release-310");
    wm.verify(
        getRequestedFor(urlPathEqualTo("/org/Shop/_apis/release/releases/310"))
            .withQueryParam("api-version", equalTo("7.1")));
  }

  @Test
  void aProjectNameWithASpaceIsEncodedInThePath() {
    wm.stubFor(
        get(urlPathEqualTo("/org/My%20Shop/_apis/release/releases/1"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody("{\"id\":1}")));

    assertThat(hub().release("My Shop", "1").path("id").asInt()).isEqualTo(1);
  }

  @Test
  void anErrorStatusIsRaisedSoTheRoundIsRecordedAsFailed() {
    wm.stubFor(
        get(urlPathEqualTo("/org/Shop/_apis/release/releases/9"))
            .willReturn(aResponse().withStatus(401)));

    assertThatThrownBy(() -> hub().release("Shop", "9"))
        .isInstanceOf(HttpClientErrorException.Unauthorized.class);
  }
}
