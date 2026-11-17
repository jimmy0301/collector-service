package io.cmdb.collector.inventory.domain.artifact;

/**
 * Write side of artifacts: created the first time an image digest appears (PLAN.md 3.2: does not
 * depend on ACR events).
 */
public interface ArtifactStore {

  /**
   * Unique key is (registry, repository, digest); skipped if it already exists. build_id is filled
   * in later from ADO data.
   */
  void ensureExists(String registry, String repository, String tag, String digest);
}
