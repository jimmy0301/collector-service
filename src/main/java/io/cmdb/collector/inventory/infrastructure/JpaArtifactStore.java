package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.artifact.ArtifactStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaArtifactStore implements ArtifactStore {

  private final SpringDataArtifactRepository artifacts;

  JpaArtifactStore(SpringDataArtifactRepository artifacts) {
    this.artifacts = artifacts;
  }

  @Override
  @Transactional
  public void ensureExists(String registry, String repository, String tag, String digest) {
    artifacts.insertIfAbsent(registry, repository, tag, digest);
  }
}
