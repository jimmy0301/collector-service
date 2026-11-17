package io.cmdb.collector.http;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient used to call the two integration services: Bearer token, timeouts, gzip; it also reads
 * the JVM proxy and trust store settings (https.proxyHost, javax.net.ssl.trustStore), so the
 * corporate proxy / CA is handled with JVM parameters.
 */
public final class HubRestClients {

  private HubRestClients() {}

  public static RestClient create(
      RestClient.Builder builder,
      String baseUrl,
      String token,
      Duration connectTimeout,
      Duration readTimeout) {
    return build(
        builder,
        baseUrl,
        token == null || token.isBlank() ? null : "Bearer " + token,
        connectTimeout,
        readTimeout);
  }

  /**
   * Same client with a complete Authorization header value (for example Azure DevOps' {@code Basic
   * base64(":" + PAT)}); null means no Authorization header.
   */
  public static RestClient withAuthorization(
      RestClient.Builder builder,
      String baseUrl,
      String authorization,
      Duration connectTimeout,
      Duration readTimeout) {
    return build(builder, baseUrl, authorization, connectTimeout, readTimeout);
  }

  private static RestClient build(
      RestClient.Builder builder,
      String baseUrl,
      String authorization,
      Duration connectTimeout,
      Duration readTimeout) {
    CloseableHttpClient http =
        HttpClients.custom()
            .useSystemProperties()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .useSystemProperties()
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.of(connectTimeout))
                            .build())
                    .build())
            .build();
    HttpComponentsClientHttpRequestFactory factory =
        new HttpComponentsClientHttpRequestFactory(http);
    factory.setReadTimeout(readTimeout);

    RestClient.Builder b =
        builder
            .requestFactory(factory)
            .baseUrl(baseUrl == null ? "http://unconfigured.invalid" : baseUrl);
    if (authorization != null && !authorization.isBlank()) {
      b = b.defaultHeader(HttpHeaders.AUTHORIZATION, authorization);
    }
    return b.build();
  }
}
