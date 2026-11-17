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

/** Row mapping of clusters (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "clusters")
class ClusterEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String code;
  private Long siteId;
  private String provider;
  private String externalId;
  private String argoServerUrl;
  private String argoClusterName;
  private String name;
  private String environment;
  private String region;
  private String status;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode metadata;

  protected ClusterEntity() {}
}
