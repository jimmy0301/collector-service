package io.cmdb.collector.inventory.domain.argo;

/**
 * A resource Argo manages for an Application (resource-tree {@code nodes[]}): identity and Argo's
 * health judgment. kind, namespace and name are needed to create a placeholder row when K8s has not
 * reported the resource yet.
 */
public record ArgoResourceNode(
    String uid, String kind, String namespace, String name, String healthStatus) {}
