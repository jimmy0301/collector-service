package io.cmdb.collector.argo.domain;

/**
 * A site reported by the integration service (GET /sites). name and region are null when sites are
 * specified by configuration.
 */
public record Site(String code, String name, String region) {}
