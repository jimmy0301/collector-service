package io.cmdb.collector.inventory.domain.site;

/**
 * Write side of sites.
 *
 * <p>These *Store interfaces are not DDD Repositories: these tables only get "upsert from a source
 * snapshot" and have no domain behavior, so they are not modeled as aggregates (Repository is only
 * for AggregateRoot).
 */
public interface SiteStore {

  /** Inserts or updates; a null name or region in the snapshot keeps the existing value. */
  void upsert(SiteSnapshot site);

  /** Creates only if absent (name temporarily uses the code); does not overwrite existing data. */
  void ensureExists(String code);
}
