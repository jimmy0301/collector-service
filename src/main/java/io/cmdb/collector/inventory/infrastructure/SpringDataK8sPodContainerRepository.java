package io.cmdb.collector.inventory.infrastructure;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private: used only by {@link JpaK8sResourceStore}. */
interface SpringDataK8sPodContainerRepository extends JpaRepository<K8sPodContainerEntity, Long> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO k8s_pod_containers
            (k8s_resource_id, container_name, is_init, image, image_id, image_digest, ready,
             restart_count, state, state_reason, last_terminated_reason, last_exit_code,
             last_terminated_at, observed_at)
          VALUES (:pod, :name, :init, CAST(:image AS text), CAST(:imageId AS text),
                  CAST(:digest AS text), CAST(:ready AS boolean), :restarts,
                  CAST(:state AS text), CAST(:stateReason AS text), CAST(:lastReason AS text),
                  CAST(:lastExit AS integer), CAST(:lastAt AS timestamptz), :observedAt)
          ON CONFLICT (k8s_resource_id, container_name, is_init) DO UPDATE SET
            image = EXCLUDED.image, image_id = EXCLUDED.image_id,
            image_digest = EXCLUDED.image_digest, ready = EXCLUDED.ready,
            restart_count = EXCLUDED.restart_count, state = EXCLUDED.state,
            state_reason = EXCLUDED.state_reason,
            last_terminated_reason = EXCLUDED.last_terminated_reason,
            last_exit_code = EXCLUDED.last_exit_code,
            last_terminated_at = EXCLUDED.last_terminated_at, observed_at = EXCLUDED.observed_at
          WHERE k8s_pod_containers.observed_at <= EXCLUDED.observed_at
          """,
      nativeQuery = true)
  void upsertIfNewer(
      @Param("pod") long podId,
      @Param("name") String name,
      @Param("init") boolean init,
      @Param("image") String image,
      @Param("imageId") String imageId,
      @Param("digest") String digest,
      @Param("ready") Boolean ready,
      @Param("restarts") int restarts,
      @Param("state") String state,
      @Param("stateReason") String stateReason,
      @Param("lastReason") String lastReason,
      @Param("lastExit") Integer lastExit,
      @Param("lastAt") Instant lastAt,
      @Param("observedAt") Instant observedAt);
}
