package io.cmdb.collector.ado.infrastructure;

import io.cmdb.collector.ado.domain.AdoHub;
import io.cmdb.collector.http.HubRestClients;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
class HttpAdoHub implements AdoHub {

  private static final String API_VERSION = "7.1";
  private static final String CONTINUATION_HEADER = "x-ms-continuationtoken";

  private final RestClient client;
  private final AdoProperties props;

  HttpAdoHub(RestClient.Builder builder, AdoProperties props) {
    this.props = props;
    // Azure DevOps PAT: Basic authentication with an empty user name
    String token = props.token();
    String authorization =
        token == null || token.isBlank()
            ? null
            : "Basic "
                + Base64.getEncoder()
                    .encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
    this.client =
        HubRestClients.withAuthorization(
            builder, props.baseUrl(), authorization, props.connectTimeout(), props.readTimeout());
  }

  @Override
  public List<JsonNode> deploymentsModifiedSince(String project, Instant since) {
    List<JsonNode> all = new ArrayList<>();
    String continuation = null;
    do {
      String token = continuation;
      ResponseEntity<JsonNode> response =
          client
              .get()
              .uri(
                  uri -> {
                    uri.path("/{org}/{project}/_apis/release/deployments")
                        .queryParam("api-version", API_VERSION)
                        .queryParam("minModifiedTime", since.toString())
                        .queryParam("queryOrder", "ascending")
                        .queryParam("$top", props.pageSize());
                    if (token != null) {
                      uri.queryParam("continuationToken", "{token}");
                      return uri.build(props.organization(), project, token);
                    }
                    return uri.build(props.organization(), project);
                  })
              .retrieve()
              .toEntity(JsonNode.class);
      JsonNode body = response.getBody();
      if (body != null) {
        body.path("value").forEach(all::add);
      }
      continuation = response.getHeaders().getFirst(CONTINUATION_HEADER);
    } while (continuation != null && !continuation.isBlank());
    return all;
  }

  @Override
  public JsonNode release(String project, String releaseId) {
    return client
        .get()
        .uri(
            "/{org}/{project}/_apis/release/releases/{id}?api-version=" + API_VERSION,
            props.organization(),
            project,
            releaseId)
        .retrieve()
        .body(JsonNode.class);
  }
}
