package io.cmdb.collector.argo;

/**
 * spec.destination of an Argo Application (public API of the module; the K8s module uses it to
 * decide which namespaces to query).
 */
public record Destination(String server, String name, String namespace) {}
