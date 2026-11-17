package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * Row mapping of sites. Used only so {@code ddl-auto=validate} covers this table and for read-only
 * queries; writes go through the native upsert in {@link SpringDataSiteRepository}. Not an
 * aggregate (see inventory/CLAUDE.md).
 */
@Entity
@Immutable
@Table(name = "sites")
class SiteEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String code;
  private String name;
  private String region;
  private String status;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode metadata;

  protected SiteEntity() {}
}
