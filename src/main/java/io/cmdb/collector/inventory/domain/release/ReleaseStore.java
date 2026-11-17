package io.cmdb.collector.inventory.domain.release;

/** Write side of releases and release_deployments (Azure DevOps CD). */
public interface ReleaseStore {

  /**
   * Inserts or updates a release; an older observation ({@code modifiedAt}) never overwrites a
   * newer one, and a value that is null never blanks an existing one. {@code pipeline_id} is
   * resolved from a registered release definition when there is one.
   *
   * @return the release id
   */
  long upsert(ReleaseSnapshot release);

  /**
   * Makes sure the release exists (a deployment can be processed before its release is). The stub
   * is overwritten by {@link #upsert}.
   *
   * @return the release id
   */
  long ensureStub(
      String project,
      String externalReleaseId,
      String definitionId,
      String definitionName,
      String name);

  /**
   * Inserts or updates a deployment by (release, stage instance, attempt); an older {@code
   * lastModifiedAt} never overwrites a newer one. {@code runtime_target_id} is resolved from the
   * stage's definition environment id when exactly one runtime target is registered for it.
   */
  void upsertDeployment(ReleaseDeploymentSnapshot deployment);
}
