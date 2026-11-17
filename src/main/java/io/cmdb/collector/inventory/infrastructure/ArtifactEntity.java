package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Row mapping of artifacts (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "artifacts")
class ArtifactEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long buildId;
  private String registry;
  private String repository;
  private String tag;
  private String digest;
  private Instant createdAt;

  protected ArtifactEntity() {}
}
