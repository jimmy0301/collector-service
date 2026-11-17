package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.k8s.K8sResourceState;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceState.ContainerImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Extracts the key state recorded in the history (see k8s_resource_history) from a native
 * Deployment, ReplicaSet or Pod object. Anything that is not in here (labels, resourceVersion,
 * conditions, timestamps...) changes all the time and must not start a new history row.
 */
final class K8sResourceStates {

  private K8sResourceStates() {}

  static K8sResourceState of(String kind, JsonNode object) {
    boolean terminating = !object.path("metadata").path("deletionTimestamp").asString("").isBlank();
    if ("Pod".equals(kind)) {
      JsonNode statuses = object.path("status").path("containerStatuses");
      return new K8sResourceState(
          object.path("status").path("phase").asString(null),
          restartCount(statuses),
          null,
          null,
          podImages(statuses),
          terminating);
    }
    JsonNode spec = object.path("spec");
    JsonNode status = object.path("status");
    return new K8sResourceState(
        null,
        0,
        spec.has("replicas") ? spec.path("replicas").asInt() : null,
        // Kubernetes omits readyReplicas when it is 0
        status.isObject() ? status.path("readyReplicas").asInt(0) : null,
        specImages(spec.path("template").path("spec").path("containers")),
        terminating);
  }

  /** sum(containerStatuses[].restartCount), init containers excluded (see SOURCE_MAPPING.md). */
  static int restartCount(JsonNode containerStatuses) {
    int total = 0;
    for (JsonNode cs : containerStatuses) {
      total += cs.path("restartCount").asInt(0);
    }
    return total;
  }

  private static List<ContainerImage> podImages(JsonNode containerStatuses) {
    List<ContainerImage> images = new ArrayList<>();
    for (JsonNode cs : containerStatuses) {
      images.add(
          new ContainerImage(
              cs.path("name").asString(""),
              cs.path("image").asString(null),
              ImageReference.digestOf(cs.path("imageID").asString(null)).orElse(null)));
    }
    return sorted(images);
  }

  private static List<ContainerImage> specImages(JsonNode containers) {
    List<ContainerImage> images = new ArrayList<>();
    for (JsonNode c : containers) {
      images.add(
          new ContainerImage(c.path("name").asString(""), c.path("image").asString(null), null));
    }
    return sorted(images);
  }

  private static List<ContainerImage> sorted(List<ContainerImage> images) {
    images.sort(Comparator.comparing(ContainerImage::name));
    return images;
  }
}
