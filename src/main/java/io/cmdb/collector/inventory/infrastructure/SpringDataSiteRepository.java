package io.cmdb.collector.inventory.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * package-private: used only by {@link JpaSiteStore}. The upsert is native SQL; the caller must be
 * in a read-write transaction.
 */
interface SpringDataSiteRepository extends JpaRepository<SiteEntity, Long> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO sites (code, name, region)
          VALUES (:code, COALESCE(CAST(:name AS text), :code), CAST(:region AS text))
          ON CONFLICT (code) DO UPDATE SET
            name = COALESCE(CAST(:name AS text), sites.name),
            region = COALESCE(CAST(:region AS text), sites.region)
          """,
      nativeQuery = true)
  int upsert(
      @Param("code") String code, @Param("name") String name, @Param("region") String region);

  @Modifying
  @Query(
      value = "INSERT INTO sites (code, name) VALUES (:code, :code) ON CONFLICT (code) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(@Param("code") String code);
}
