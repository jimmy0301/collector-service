package io.cmdb.collector.inventory.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private: used only by {@link JpaArtifactStore}. */
interface SpringDataArtifactRepository extends JpaRepository<ArtifactEntity, Long> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO artifacts (registry, repository, tag, digest)
          VALUES (:registry, :repository, CAST(:tag AS text), :digest)
          ON CONFLICT (registry, repository, digest) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("registry") String registry,
      @Param("repository") String repository,
      @Param("tag") String tag,
      @Param("digest") String digest);
}
