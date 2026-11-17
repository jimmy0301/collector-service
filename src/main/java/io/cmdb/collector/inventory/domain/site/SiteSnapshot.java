package io.cmdb.collector.inventory.domain.site;

/**
 * A site. A null name or region means the source did not provide it and the existing value is not
 * overwritten.
 */
public record SiteSnapshot(String code, String name, String region) {}
