package io.cmdb.collector.rawevent.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redaction before writing (PLAN.md 3.1): removes env values, envFrom, managedFields,
 * last-applied-configuration and Helm values, and masks string fields whose name looks like a
 * password / token. Returns new JSON; the input is not modified.
 */
public final class PayloadRedactor {

  public static final String MASK = "***";

  private static final Pattern SENSITIVE_KEY =
      Pattern.compile(
          "(?i).*(password|passwd|token|api[-_]?key|private[-_]?key|client[-_]?secret|credential).*");
  private static final String LAST_APPLIED = "kubectl.kubernetes.io/last-applied-configuration";

  private PayloadRedactor() {}

  public static JsonNode redact(JsonNode input) {
    JsonNode copy = input.deepCopy();
    walk(copy);
    return copy;
  }

  private static void walk(JsonNode node) {
    if (node.isArray()) {
      node.forEach(PayloadRedactor::walk);
      return;
    }
    if (!(node instanceof ObjectNode obj)) {
      return;
    }
    obj.remove("managedFields");
    obj.remove("envFrom");
    if (obj.get("env") != null && obj.get("env").isArray()) {
      obj.get("env").forEach(e -> removeFrom(e, "value"));
    }
    if (obj.get("helm") instanceof ObjectNode helm) {
      helm.remove("values");
      helm.remove("valuesObject");
      if (helm.get("parameters") != null) {
        helm.get("parameters").forEach(p -> removeFrom(p, "value"));
      }
    }
    if (obj.get("annotations") instanceof ObjectNode annotations) {
      annotations.remove(LAST_APPLIED);
    }
    List<String> names = new ArrayList<>(obj.propertyNames());
    for (String name : names) {
      JsonNode child = obj.get(name);
      if (child.isString() && SENSITIVE_KEY.matcher(name).matches()) {
        obj.put(name, MASK);
      } else {
        walk(child);
      }
    }
  }

  private static void removeFrom(JsonNode node, String field) {
    if (node instanceof ObjectNode o) {
      o.remove(field);
    }
  }
}
