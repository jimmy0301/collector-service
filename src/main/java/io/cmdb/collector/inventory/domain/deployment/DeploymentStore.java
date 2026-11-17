package io.cmdb.collector.inventory.domain.deployment;

/** Write side of deployments: the cluster-side fact of a rollout. */
public interface DeploymentStore {

  /**
   * Records an Argo sync operation as a deployment of the Application, idempotently by {@code
   * operationKey}:
   *
   * <ul>
   *   <li>a RUNNING deployment is updated while it is still RUNNING;
   *   <li>SUCCEEDED / FAILED are final facts: they replace RUNNING (and SUPERSEDED, when the finish
   *       is seen late) and are never changed again;
   *   <li>a RUNNING deployment of the same Application that started before another one is marked
   *       SUPERSEDED (Argo keeps only the latest operation, so the end of the older one may never
   *       be seen).
   * </ul>
   *
   * service_id and runtime_target_id are copied from the Application when it has them; the release
   * and the CI build are not resolved here.
   */
  void recordArgoOperation(long applicationId, ArgoOperationSnapshot operation);
}
