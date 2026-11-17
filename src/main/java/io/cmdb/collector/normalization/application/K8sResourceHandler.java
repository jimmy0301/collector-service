package io.cmdb.collector.normalization.application;

import io.cmdb.collector.inventory.domain.artifact.ArtifactStore;
import io.cmdb.collector.inventory.domain.cluster.ClusterSnapshot;
import io.cmdb.collector.inventory.domain.cluster.ClusterStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceHistoryStore;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceSnapshot;
import io.cmdb.collector.inventory.domain.k8s.K8sResourceStore;
import io.cmdb.collector.inventory.domain.k8s.PodContainerSnapshot;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * K8S Deployment, ReplicaSet, Pod ({site, cluster:{id,name}, fetchedAt, object}) → k8s_resources,
 * k8s_resource_relationships (ownerReferences), k8s_pod_containers, and artifacts created the first
 * time a Pod's image digest appears (PLAN.md 9.3 step 2).
 *
 * <p>Every observation also goes to the state history ({@link K8sResourceHistoryStore}); it only
 * starts a new row when a key field changed.
 *
 * <p>service_id, runtime_target_id and argo_application_id are not filled here: they need Argo data
 * (a later step).
 */
@Component
class K8sResourceHandler implements RawEventHandler {

  private static final Set<String> KINDS = Set.of("Deployment", "ReplicaSet", "Pod");

  private final SiteStore sites;
  private final ClusterStore clusters;
  private final K8sResourceStore resources;
  private final ArtifactStore artifacts;
  private final K8sResourceHistoryStore history;

  K8sResourceHandler(
      SiteStore sites,
      ClusterStore clusters,
      K8sResourceStore resources,
      ArtifactStore artifacts,
      K8sResourceHistoryStore history) {
    this.sites = sites;
    this.clusters = clusters;
    this.resources = resources;
    this.artifacts = artifacts;
    this.history = history;
  }

  @Override
  public boolean supports(String provider, String eventType) {
    return "K8S".equals(provider) && KINDS.contains(eventType);
  }

  @Override
  public void handle(RawEvent event) {
    JsonNode p = event.payload();
    String site = Payloads.required(p, "site");
    String clusterId = Payloads.required(p.path("cluster"), "id");
    String clusterName = Payloads.required(p.path("cluster"), "name");
    Instant observedAt = Payloads.instant(p, "fetchedAt");
    JsonNode object = p.path("object");
    JsonNode meta = object.path("metadata");
    String kind = Payloads.required(object, "kind");
    String uid = Payloads.required(meta, "uid");
    String namespace = meta.path("namespace").asString(null);
    boolean pod = "Pod".equals(kind);

    // The cluster list event may not have been processed yet: make sure the site and cluster exist
    sites.ensureExists(site);
    clusters.ensureExists(new ClusterSnapshot(site, clusterId, clusterName, null));

    JsonNode containerStatuses = object.path("status").path("containerStatuses");
    long resourceId =
        resources.upsert(
            new K8sResourceSnapshot(
                clusterId,
                uid,
                namespace,
                kind,
                Payloads.required(meta, "name"),
                object.path("apiVersion").asString(null),
                pod ? K8sResourceStates.restartCount(containerStatuses) : 0,
                pod ? object.path("status").path("phase").asString(null) : null,
                meta.get("labels"),
                meta.get("annotations"),
                pod ? null : object.get("spec"),
                pod ? null : object.get("status"),
                observedAt,
                Payloads.optionalInstant(meta.path("deletionTimestamp"))));

    history.record(resourceId, K8sResourceStates.of(kind, object), observedAt);

    for (JsonNode owner : meta.path("ownerReferences")) {
      String ownerKind = owner.path("kind").asString("");
      if (KINDS.contains(ownerKind)) {
        long parentId =
            resources.ensureStub(
                clusterId,
                ownerKind,
                owner.path("name").asString(""),
                Payloads.required(owner, "uid"),
                namespace);
        resources.linkOwner(parentId, resourceId);
      }
    }

    if (pod) {
      containers(resourceId, containerStatuses, false, observedAt);
      containers(resourceId, object.path("status").path("initContainerStatuses"), true, observedAt);
    }
  }

  private void containers(long podId, JsonNode statuses, boolean init, Instant observedAt) {
    for (JsonNode cs : statuses) {
      String image = cs.path("image").asString(null);
      String imageId = cs.path("imageID").asString(null);
      String digest = ImageReference.digestOf(imageId).orElse(null);
      resources.upsertContainer(podId, container(cs, init, image, imageId, digest), observedAt);
      if (digest != null) {
        ImageReference.parse(image)
            .ifPresent(
                ref -> artifacts.ensureExists(ref.registry(), ref.repository(), ref.tag(), digest));
      }
    }
  }

  private static PodContainerSnapshot container(
      JsonNode cs, boolean init, String image, String imageId, String digest) {
    JsonNode state = cs.path("state");
    String stateKey =
        state.isObject() && !state.isEmpty() ? state.propertyNames().iterator().next() : null;
    JsonNode terminated = cs.path("lastState").path("terminated");
    return new PodContainerSnapshot(
        Payloads.required(cs, "name"),
        init,
        image,
        imageId,
        digest,
        cs.has("ready") ? cs.path("ready").asBoolean() : null,
        cs.path("restartCount").asInt(0),
        stateKey,
        stateKey == null ? null : state.path(stateKey).path("reason").asString(null),
        terminated.path("reason").asString(null),
        terminated.has("exitCode") ? terminated.path("exitCode").asInt() : null,
        Payloads.optionalInstant(terminated.path("finishedAt")));
  }
}
