package io.cmdb.collector.inventory.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Row mapping of k8s_pod_containers (same purpose as {@link SiteEntity}). */
@Entity
@Immutable
@Table(name = "k8s_pod_containers")
class K8sPodContainerEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long k8sResourceId;
  private String containerName;
  private boolean isInit;
  private String image;
  private String imageId;
  private String imageDigest;
  private Boolean ready;
  private int restartCount;
  private String state;
  private String stateReason;
  private String lastTerminatedReason;
  private Integer lastExitCode;
  private Instant lastTerminatedAt;
  private Instant observedAt;

  protected K8sPodContainerEntity() {}
}
