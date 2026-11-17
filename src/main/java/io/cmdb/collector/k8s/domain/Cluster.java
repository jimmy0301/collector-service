package io.cmdb.collector.k8s.domain;

/**
 * A cluster reported by the K8s integration service. id is the stable identity (used in event IDs
 * and ordering keys); name and server are used to match Argo destinations.
 */
public record Cluster(String id, String name, String server) {}
