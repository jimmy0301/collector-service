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

/** Row mapping of argo_applications (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "argo_applications")
class ArgoApplicationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long serviceId;
  private Long runtimeTargetId;
  private Long clusterId;
  private String argoServer;
  private String externalUid;
  private String name;
  private String project;
  private String destinationServer;
  private String destinationName;
  private String destinationNamespace;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode sources;

  private String syncStatus;
  private String healthStatus;
  private Boolean autoSync;

  @JdbcTypeCode(SqlTypes.JSON)
  private JsonNode syncRevisions;

  private Instant observedAt;

  protected ArgoApplicationEntity() {}
}
