package io.cmdb.collector.k8s.domain;

import java.util.regex.Pattern;

/**
 * The integration service exposes only these four kinds (INTEGRATION_API_SPEC.md chapter 5); do not
 * add secrets / configmaps.
 */
public enum ResourceKind {
  DEPLOYMENT("Deployment", "apps/v1", "/apis/apps/v1", "deployments"),
  REPLICASET("ReplicaSet", "apps/v1", "/apis/apps/v1", "replicasets"),
  POD("Pod", "v1", "/api/v1", "pods"),
  EVENT("Event", "v1", "/api/v1", "events");

  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

  private final String kind;
  private final String apiVersion;
  private final String basePath;
  private final String plural;

  ResourceKind(String kind, String apiVersion, String basePath, String plural) {
    this.kind = kind;
    this.apiVersion = apiVersion;
    this.basePath = basePath;
    this.plural = plural;
  }

  public String kind() {
    return kind;
  }

  public String apiVersion() {
    return apiVersion;
  }

  /**
   * List path, limited to one namespace. The namespace comes from Argo data, so it is validated as
   * a DNS label first to prevent path injection.
   */
  public String namespacedPath(String namespace) {
    if (namespace.length() > 63 || !DNS_LABEL.matcher(namespace).matches()) {
      throw new IllegalArgumentException("invalid namespace: " + namespace);
    }
    return basePath + "/namespaces/" + namespace + "/" + plural;
  }
}
