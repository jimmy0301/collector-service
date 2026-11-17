package io.cmdb.collector.rawevent.domain;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PayloadRedactorTest {

  @Test
  void removesEnvValuesButKeepsNamesAndValueFrom() {
    JsonNode in =
        json(
            """
            {"spec":{"template":{"spec":{"containers":[{"name":"app","image":"x@sha256:1",
              "env":[{"name":"DB_PASS","value":"hunter2"},
                     {"name":"K","valueFrom":{"secretKeyRef":{"name":"s","key":"k"}}}],
              "envFrom":[{"secretRef":{"name":"s"}}]}]}}}}
            """);

    JsonNode out = PayloadRedactor.redact(in);

    JsonNode c = out.at("/spec/template/spec/containers/0");
    assertThat(c.has("envFrom")).isFalse();
    assertThat(c.at("/env/0/name").asString()).isEqualTo("DB_PASS");
    assertThat(c.at("/env/0").has("value")).isFalse();
    assertThat(c.at("/env/1/valueFrom/secretKeyRef/name").asString()).isEqualTo("s");
    assertThat(c.path("image").asString()).isEqualTo("x@sha256:1"); // other fields are untouched
  }

  @Test
  void removesManagedFieldsLastAppliedAndHelmValues() {
    JsonNode in =
        json(
            """
            {"metadata":{"managedFields":[{"a":1}],
               "annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{...secret...}","keep":"me"}},
             "spec":{"source":{"helm":{"values":"password: x","valuesObject":{"a":1},
               "parameters":[{"name":"p","value":"v"}]}}}}
            """);

    JsonNode out = PayloadRedactor.redact(in);

    assertThat(out.at("/metadata").has("managedFields")).isFalse();
    assertThat(
            out.at("/metadata/annotations").has("kubectl.kubernetes.io/last-applied-configuration"))
        .isFalse();
    assertThat(out.at("/metadata/annotations/keep").asString()).isEqualTo("me");
    assertThat(out.at("/spec/source/helm").has("values")).isFalse();
    assertThat(out.at("/spec/source/helm").has("valuesObject")).isFalse();
    assertThat(out.at("/spec/source/helm/parameters/0").has("value")).isFalse();
  }

  @Test
  void masksSensitiveLookingStringFieldsAnywhere() {
    JsonNode out =
        PayloadRedactor.redact(
            json(
                "{\"a\":{\"bearerToken\":\"abc\",\"dbPassword\":\"x\",\"secretKeyRef\":{\"name\":\"n\"},\"name\":\"ok\"}}"));

    assertThat(out.at("/a/bearerToken").asString()).isEqualTo(PayloadRedactor.MASK);
    assertThat(out.at("/a/dbPassword").asString()).isEqualTo(PayloadRedactor.MASK);
    assertThat(out.at("/a/secretKeyRef/name").asString()).isEqualTo("n");
    assertThat(out.at("/a/name").asString()).isEqualTo("ok");
  }

  @Test
  void doesNotMutateInput() {
    JsonNode in = json("{\"env\":[{\"name\":\"A\",\"value\":\"v\"}]}");
    PayloadRedactor.redact(in);
    assertThat(in.at("/env/0/value").asString()).isEqualTo("v");
  }
}
