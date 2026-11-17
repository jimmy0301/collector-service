package io.cmdb.collector.inventory.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private: used only by {@link JpaK8sResourceStore}. */
interface SpringDataK8sResourceRelationshipRepository
    extends JpaRepository<K8sResourceRelationshipEntity, K8sResourceRelationshipEntity.Key> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO k8s_resource_relationships (parent_resource_id, child_resource_id, relationship_type)
          VALUES (:parent, :child, :type)
          ON CONFLICT DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("parent") long parent, @Param("child") long child, @Param("type") String type);
}
