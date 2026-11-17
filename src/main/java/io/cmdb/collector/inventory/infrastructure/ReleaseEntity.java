package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** Row mapping of releases (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "releases")
class ReleaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long pipelineId;
  private String project;
  private String externalReleaseId;
  private String externalDefinitionId;
  private String definitionName;
  private String name;
  private String status;
  private String reason;
  private Long ciBuildId;
  private String artifactAlias;
  private String artifactBuildRef;
  private String sourceBranch;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode artifacts;

  private String createdBy;
  private Instant createdAt;
  private Instant modifiedAt;

  protected ReleaseEntity() {}
}
