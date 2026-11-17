package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import org.hibernate.annotations.Immutable;

/**
 * Row mapping of k8s_resource_relationships (composite primary key; same purpose as {@link
 * SiteEntity}).
 */
@Entity
@Immutable
@Table(name = "k8s_resource_relationships")
class K8sResourceRelationshipEntity {

  @EmbeddedId private Key id;

  protected K8sResourceRelationshipEntity() {}

  @Embeddable
  record Key(Long parentResourceId, Long childResourceId, String relationshipType)
      implements Serializable {}
}
