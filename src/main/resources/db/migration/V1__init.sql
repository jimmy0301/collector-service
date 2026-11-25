-- CMDB Phase 1 schema (28 business tables, plus 1 framework table event_publication)
-- Target: PostgreSQL (verified on 17; no syntax introduced after 17). This file is the Flyway V1 migration, applied by collector-service at startup (decision 2026-11-15). Until the first deployment V1 may still be edited; after that, every change must be a new V2+ file.
-- Conventions: PK BIGINT GENERATED ALWAYS AS IDENTITY; external identity uses code; times are always TIMESTAMPTZ (stored as UTC internally,
--   defaults use now(), independent of the connection time zone); JSON columns use JSONB and hold only flexible / raw data.
-- Column descriptions are in DATA_DICTIONARY.md; design context is in PLAN.md.
--
-- 2026-11-14 converted from the MariaDB 11.8 version to PostgreSQL (the database choice changed). Differences from the MariaDB version:
--   * AUTO_INCREMENT → IDENTITY; DATETIME(3) → TIMESTAMPTZ; JSON → JSONB.
--   * ON UPDATE CURRENT_TIMESTAMP → BEFORE UPDATE trigger (set_updated_at, used on systems and services).
--   * Generated column k8s_resources.cmdb_service_code: JSON_EXTRACT ... PERSISTENT → labels ->> '...' STORED.
--   * Postgres does not create indexes on foreign key columns automatically (MariaDB does), so foreign key indexes are added at the end of the file; nullable foreign keys use a partial index.
--   * The pending-work index of integration_raw_events became a partial index (only PENDING / FAILED; the Processor picks work with
--     SELECT ... FOR UPDATE SKIP LOCKED, which has no version restriction in Postgres).
--   * integration_sync_runs gets a started_at index (the purge deletes by started_at; originally only indexes starting with provider existed).
--   * Prefix indexes external_id(255) / external_id(191) became full-column indexes.
-- Verification (PostgreSQL 17): all 28 tables are created; every foreign key column has an index; the updated_at trigger, generated column, draft-service CHECK,
--   ON CONFLICT idempotent writes and the pending partial index were all tested; the recursive impact query of PLAN.md 6.3 (including cycles) runs as is;
--   the collector's integration tests load this file directly.
-- Not yet verified: index performance with large data, the actual Flyway migration flow, and the query comparing the same service's digest across sites (needs to be rewritten and verified on Postgres).

-- automatic updated_at maintenance (replaces MariaDB's ON UPDATE CURRENT_TIMESTAMP)
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END
$$;

-- ===== Core =====
CREATE TABLE teams (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  email VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_team_name UNIQUE (name)
);

CREATE TABLE systems (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code VARCHAR(100) NOT NULL,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  owner_team_id BIGINT,
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_system_code UNIQUE (code),
  CONSTRAINT fk_system_team FOREIGN KEY (owner_team_id) REFERENCES teams(id)
);
CREATE TRIGGER trg_systems_updated_at BEFORE UPDATE ON systems
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE services (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  system_id BIGINT,                        -- a draft service may be NULL (lifecycle_status=DRAFT); required before it becomes ACTIVE (see CHECK)
  code VARCHAR(100) NOT NULL,              -- SVC-001, i.e. cmdb.io/service-id
  name VARCHAR(200) NOT NULL,
  description TEXT,
  service_type VARCHAR(50),                -- API / WORKER / BATCH
  owner_team_id BIGINT,
  criticality VARCHAR(30),                 -- LOW / MEDIUM / HIGH / CRITICAL
  lifecycle_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  metadata JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_service_code UNIQUE (code),
  CONSTRAINT fk_service_system FOREIGN KEY (system_id) REFERENCES systems(id),
  CONSTRAINT fk_service_team FOREIGN KEY (owner_team_id) REFERENCES teams(id),
  CONSTRAINT ck_service_system CHECK (lifecycle_status = 'DRAFT' OR system_id IS NOT NULL)
);
CREATE TRIGGER trg_services_updated_at BEFORE UPDATE ON services
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_service_system ON services (system_id);

-- non-microservice resources such as MongoDB / NATS / Redis
CREATE TABLE external_resources (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code VARCHAR(100) NOT NULL,
  name VARCHAR(200) NOT NULL,
  resource_type VARCHAR(50) NOT NULL,      -- DATABASE / MESSAGE_BROKER / CACHE / STORAGE
  environment VARCHAR(50),
  endpoint VARCHAR(500),
  owner_team_id BIGINT,
  metadata JSONB,
  CONSTRAINT uk_ext_res_code UNIQUE (code),
  CONSTRAINT fk_extres_team FOREIGN KEY (owner_team_id) REFERENCES teams(id)
);

-- source calls / depends on target
CREATE TABLE service_dependencies (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_service_id BIGINT NOT NULL,
  target_service_id BIGINT NOT NULL,
  dependency_type VARCHAR(50) NOT NULL,    -- HTTP / GRPC / MESSAGE
  criticality VARCHAR(30),
  discovered_by VARCHAR(30) NOT NULL DEFAULT 'MANUAL',   -- MANUAL / AUTO
  valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  valid_to TIMESTAMPTZ,
  CONSTRAINT fk_dep_src FOREIGN KEY (source_service_id) REFERENCES services(id),
  CONSTRAINT fk_dep_tgt FOREIGN KEY (target_service_id) REFERENCES services(id),
  CONSTRAINT ck_dep_self CHECK (source_service_id <> target_service_id)
);
CREATE INDEX idx_dep_source ON service_dependencies (source_service_id);
CREATE INDEX idx_dep_target ON service_dependencies (target_service_id);

CREATE TABLE service_resource_dependencies (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL,
  resource_id BIGINT NOT NULL,
  usage_type VARCHAR(50),                  -- READ_WRITE / PUBLISH / SUBSCRIBE
  criticality VARCHAR(30),
  valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  valid_to TIMESTAMPTZ,
  CONSTRAINT fk_srd_service FOREIGN KEY (service_id) REFERENCES services(id),
  CONSTRAINT fk_srd_resource FOREIGN KEY (resource_id) REFERENCES external_resources(id)
);
CREATE INDEX idx_srd_service ON service_resource_dependencies (service_id);
CREATE INDEX idx_srd_resource ON service_resource_dependencies (resource_id);

-- ===== Integration Mapping =====
CREATE TABLE repositories (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider VARCHAR(30) NOT NULL,           -- AZURE_DEVOPS / GITHUB
  external_id VARCHAR(200) NOT NULL,
  organization VARCHAR(200),
  project VARCHAR(200),
  name VARCHAR(200) NOT NULL,
  url VARCHAR(1000),
  metadata JSONB,
  CONSTRAINT uk_repo UNIQUE (provider, external_id)
);

CREATE TABLE service_repositories (
  service_id BIGINT NOT NULL,
  repository_id BIGINT NOT NULL,
  repository_role VARCHAR(30) NOT NULL,    -- APPLICATION / MANIFEST / HELM
  environment VARCHAR(50),                 -- fill in when the MANIFEST repo is split per environment
  PRIMARY KEY (service_id, repository_id, repository_role),
  CONSTRAINT fk_sr_service FOREIGN KEY (service_id) REFERENCES services(id),
  CONSTRAINT fk_sr_repo FOREIGN KEY (repository_id) REFERENCES repositories(id)
);

-- Site: every site has its own Argo CD and K8s clusters; data reported by the integration services is identified by site
CREATE TABLE sites (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code VARCHAR(100) NOT NULL,              -- site code, the same as the site identity reported by the integration services
  name VARCHAR(200) NOT NULL,
  region VARCHAR(100),
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  metadata JSONB,
  CONSTRAINT uk_site_code UNIQUE (code)
);

CREATE TABLE clusters (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code VARCHAR(100) NOT NULL,              -- aks-prod-001 (stable custom ID)
  site_id BIGINT,                          -- owning site → sites
  provider VARCHAR(30) NOT NULL DEFAULT 'ONPREM',   -- ONPREM (the site's own K8s) / AZURE
  external_id VARCHAR(500) NOT NULL,       -- cluster identity provided by the K8s integration service (the Azure Resource ID for AKS)
  argo_server_url VARCHAR(500),            -- corresponds to Argo destination.server
  argo_cluster_name VARCHAR(200),          -- corresponds to Argo destination.name (the cluster name inside Argo)
  name VARCHAR(200) NOT NULL,
  environment VARCHAR(50),
  region VARCHAR(100),
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  metadata JSONB,
  CONSTRAINT uk_cluster_code UNIQUE (code),
  CONSTRAINT uk_cluster_ext UNIQUE (external_id),
  CONSTRAINT fk_cluster_site FOREIGN KEY (site_id) REFERENCES sites(id)
);
CREATE INDEX idx_cluster_site ON clusters (site_id);

-- When onboarding an existing environment this table is the main mechanism for resolving serviceId (labels may not exist)
CREATE TABLE runtime_targets (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL,
  cluster_id BIGINT NOT NULL,
  namespace VARCHAR(128) NOT NULL,
  environment VARCHAR(50) NOT NULL,
  workload_kind VARCHAR(50) NOT NULL DEFAULT 'Deployment',
  workload_name VARCHAR(255) NOT NULL,
  release_definition_env_id VARCHAR(100),   -- the matching Release stage (definitionEnvironmentId)
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_runtime UNIQUE (service_id, cluster_id, namespace, workload_kind, workload_name),
  CONSTRAINT fk_rt_service FOREIGN KEY (service_id) REFERENCES services(id),
  CONSTRAINT fk_rt_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(id)
);
CREATE INDEX idx_runtime_cluster_ns ON runtime_targets (cluster_id, namespace);
CREATE INDEX idx_runtime_service_env ON runtime_targets (service_id, environment);

-- generic mapping to other platform identities (for extensions such as GitHub Actions / Flux; does not replace the formal association tables above)
CREATE TABLE external_refs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL,
  provider VARCHAR(30) NOT NULL,
  resource_type VARCHAR(50) NOT NULL,
  external_id VARCHAR(500) NOT NULL,
  external_scope VARCHAR(500),
  metadata JSONB,
  CONSTRAINT uk_extref UNIQUE (provider, resource_type, external_id, service_id),
  CONSTRAINT fk_extref_service FOREIGN KEY (service_id) REFERENCES services(id)
);

-- ===== Deployment =====
CREATE TABLE pipelines (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL,
  provider VARCHAR(30) NOT NULL DEFAULT 'AZURE_DEVOPS',
  pipeline_type VARCHAR(10) NOT NULL,      -- CI / CD. CD is a Release definition, in which case provider is AZURE_DEVOPS_RELEASE
  external_id VARCHAR(200) NOT NULL,
  name VARCHAR(200) NOT NULL,
  repository_id BIGINT,
  metadata JSONB,
  CONSTRAINT uk_pipeline UNIQUE (provider, external_id),
  CONSTRAINT fk_pl_service FOREIGN KEY (service_id) REFERENCES services(id),
  CONSTRAINT fk_pl_repo FOREIGN KEY (repository_id) REFERENCES repositories(id)
);

-- each run of a CI pipeline (azure-pipeline.yaml); CD releases are in releases
CREATE TABLE builds (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  pipeline_id BIGINT NOT NULL,
  external_build_id VARCHAR(200) NOT NULL,
  build_number VARCHAR(100),
  deployment_key VARCHAR(100),             -- correlation ID produced by the pipeline
  source_version VARCHAR(100),             -- commit SHA
  source_branch VARCHAR(200),
  status VARCHAR(30),
  result VARCHAR(30),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  CONSTRAINT uk_build UNIQUE (pipeline_id, external_build_id),
  CONSTRAINT fk_build_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipelines(id)
);
CREATE INDEX idx_build_commit ON builds (source_version);
CREATE INDEX idx_build_depkey ON builds (deployment_key);

CREATE TABLE artifacts (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  build_id BIGINT,
  registry VARCHAR(255) NOT NULL,
  repository VARCHAR(255) NOT NULL,
  tag VARCHAR(200),
  digest VARCHAR(100) NOT NULL,            -- sha256:...
  created_at TIMESTAMPTZ,
  CONSTRAINT uk_artifact UNIQUE (registry, repository, digest),
  CONSTRAINT fk_art_build FOREIGN KEY (build_id) REFERENCES builds(id)
);
CREATE INDEX idx_artifact_digest ON artifacts (digest);

CREATE TABLE argo_applications (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT,                       -- NULL when not mapped to a service (listed in the unmapped report)
  runtime_target_id BIGINT,
  cluster_id BIGINT,                       -- NULL when the destination is not yet mapped to a registered cluster
  argo_server VARCHAR(500),                -- source Argo instance, or the site identity reported by the hub (supports multiple Argo instances)
  external_uid VARCHAR(100) NOT NULL,      -- metadata.uid, unique (idempotency key)
  name VARCHAR(255) NOT NULL,
  project VARCHAR(200),
  destination_server VARCHAR(500),         -- original value of spec.destination.server
  destination_name VARCHAR(200),           -- original value of spec.destination.name
  destination_namespace VARCHAR(128),
  sources JSONB,                            -- [{repoURL,path,targetRevision}], supports multiple sources
  sync_status VARCHAR(30),
  health_status VARCHAR(30),
  auto_sync BOOLEAN,                       -- automatic sync when spec.syncPolicy.automated is set
  sync_revisions JSONB,
  observed_at TIMESTAMPTZ,
  CONSTRAINT uk_argo_uid UNIQUE (external_uid),
  CONSTRAINT fk_argo_service FOREIGN KEY (service_id) REFERENCES services(id),
  CONSTRAINT fk_argo_runtime FOREIGN KEY (runtime_target_id) REFERENCES runtime_targets(id),
  CONSTRAINT fk_argo_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(id)
);
CREATE INDEX idx_argo_cluster_name ON argo_applications (cluster_id, name);

-- Release (CD): the release itself; the artifact points to a CI build
CREATE TABLE releases (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  pipeline_id BIGINT,                       -- release definition → pipelines (pipeline_type=CD); NULL until the definition is registered
  project VARCHAR(200) NOT NULL,            -- Azure DevOps project (release ids are unique within a project)
  external_release_id VARCHAR(100) NOT NULL,
  external_definition_id VARCHAR(100) NOT NULL,  -- releaseDefinition.id, kept so pipeline_id can be filled in later
  definition_name VARCHAR(200),
  name VARCHAR(200),
  status VARCHAR(30),                       -- draft / active / abandoned
  reason VARCHAR(30),                       -- manual / continuousIntegration / schedule / pullRequest
  ci_build_id BIGINT,                       -- the CI run the primary Build artifact points to → builds
  artifact_alias VARCHAR(200),
  artifact_build_ref VARCHAR(100),          -- original value of artifacts[].definitionReference.version.id
  source_branch VARCHAR(200),               -- artifacts[].definitionReference.branch.id
  artifacts JSONB,                           -- artifacts[] reduced to alias, type, isPrimary, definitionReference (there may be several)
  created_by VARCHAR(200),
  created_at TIMESTAMPTZ,
  modified_at TIMESTAMPTZ,                  -- release.modifiedOn; NULL for a stub created from a deployment before the release was fetched
  CONSTRAINT uk_release UNIQUE (project, external_release_id),
  CONSTRAINT fk_rel_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipelines(id),
  CONSTRAINT fk_rel_build FOREIGN KEY (ci_build_id) REFERENCES builds(id)
);
CREATE INDEX idx_release_build ON releases (ci_build_id);

-- One environment deployment = (release, stage, attempt)
CREATE TABLE release_deployments (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  release_id BIGINT NOT NULL,
  external_release_env_id VARCHAR(100) NOT NULL,   -- the id of this release's stage instance
  definition_environment_id VARCHAR(100),          -- stable id of the stage within the release definition
  environment_name VARCHAR(200) NOT NULL,          -- stage name, such as dev / uat / prod
  attempt INT NOT NULL DEFAULT 1,
  external_deployment_id VARCHAR(100),             -- deployment.id of the Deployments API
  deployment_status VARCHAR(30),                   -- notDeployed / inProgress / succeeded / partiallySucceeded / failed
  operation_status VARCHAR(40),                    -- queued / pending / approved / rejected / phaseInProgress ...
  reason VARCHAR(30),                              -- manual / automated / scheduled / redeployTrigger
  requested_by VARCHAR(200),
  queued_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  last_modified_at TIMESTAMPTZ,                    -- deployment.lastModifiedOn; an older observation never overwrites a newer one
  runtime_target_id BIGINT,                        -- definition_environment_id → runtime_targets
  CONSTRAINT uk_rel_dep UNIQUE (release_id, external_release_env_id, attempt),
  CONSTRAINT fk_reldep_release FOREIGN KEY (release_id) REFERENCES releases(id),
  CONSTRAINT fk_reldep_runtime FOREIGN KEY (runtime_target_id) REFERENCES runtime_targets(id)
);
CREATE INDEX idx_reldep_env_time ON release_deployments (definition_environment_id, started_at);
CREATE INDEX idx_reldep_runtime ON release_deployments (runtime_target_id, started_at);

-- Manual approvals (ADO Release deployment-approval events and the Approvals API)
CREATE TABLE release_approvals (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  external_approval_id VARCHAR(100) NOT NULL,      -- approval.id
  release_deployment_id BIGINT,                    -- matched by release + stage + attempt
  external_release_id VARCHAR(100),
  external_release_env_id VARCHAR(100),
  approval_type VARCHAR(20),                       -- preDeploy / postDeploy
  status VARCHAR(20),                              -- pending / approved / rejected / reassigned / canceled / skipped
  attempt INT,
  is_automated BOOLEAN,
  approver_name VARCHAR(200),                      -- the assigned approver
  approved_by_name VARCHAR(200),                   -- the actual approver (only available from the Approvals API)
  comments TEXT,
  requested_at TIMESTAMPTZ,                        -- approval.createdOn
  decided_at TIMESTAMPTZ,                          -- approval.modifiedOn when the status is approved / rejected
  history JSONB,                                    -- approval.history[]
  CONSTRAINT uk_rel_approval UNIQUE (external_approval_id),
  CONSTRAINT fk_relappr_dep FOREIGN KEY (release_deployment_id) REFERENCES release_deployments(id)
);
CREATE INDEX idx_relappr_dep ON release_approvals (release_deployment_id);
CREATE INDEX idx_relappr_rel ON release_approvals (external_release_id, external_release_env_id);

CREATE TABLE deployments (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  deployment_key VARCHAR(100),
  external_operation_key VARCHAR(200),     -- idempotency key: {argo_app_uid}:{operationState.startedAt}
  service_id BIGINT,                       -- NULL when no service matches; backfilled later by the Reconciler
  runtime_target_id BIGINT,                -- same as above
  build_id BIGINT,                         -- CI run; may be NULL for manual / third-party / backfilled deployments
  release_deployment_id BIGINT,            -- the corresponding release environment deployment; NULL when none matches
  argo_application_id BIGINT,
  trigger_type VARCHAR(30) NOT NULL,       -- PIPELINE / ARGOCD / MANUAL
  git_revision VARCHAR(100),               -- manifest repo revision
  application_revision VARCHAR(100),       -- commit of the application code repo
  sync_status VARCHAR(30),
  health_status VARCHAR(30),
  deployment_status VARCHAR(30) NOT NULL,  -- RUNNING / SUCCEEDED / FAILED / SUPERSEDED
  status_message TEXT,                     -- Argo operationState.message (reason for a sync failure, etc.)
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  metadata JSONB,                           -- is_rollback, source=BACKFILL, release_artifact_mismatch, argo_operation_info, initiated_by, manifest_commit_author, etc.
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_dep_operation UNIQUE (external_operation_key),
  CONSTRAINT fk_dp_service FOREIGN KEY (service_id) REFERENCES services(id),
  CONSTRAINT fk_dp_runtime FOREIGN KEY (runtime_target_id) REFERENCES runtime_targets(id),
  CONSTRAINT fk_dp_build FOREIGN KEY (build_id) REFERENCES builds(id),
  CONSTRAINT fk_dp_release_dep FOREIGN KEY (release_deployment_id) REFERENCES release_deployments(id),
  CONSTRAINT fk_dp_argo FOREIGN KEY (argo_application_id) REFERENCES argo_applications(id)
);
CREATE INDEX idx_dep_service_time ON deployments (service_id, started_at);
CREATE INDEX idx_dep_runtime_time ON deployments (runtime_target_id, started_at);
CREATE INDEX idx_dep_argo_time ON deployments (argo_application_id, started_at);
CREATE INDEX idx_dep_build ON deployments (build_id);
CREATE INDEX idx_dep_release_dep ON deployments (release_deployment_id);
CREATE INDEX idx_dep_key ON deployments (deployment_key);

CREATE TABLE deployment_artifacts (
  deployment_id BIGINT NOT NULL,
  artifact_id BIGINT NOT NULL,
  container_name VARCHAR(200) NOT NULL,
  PRIMARY KEY (deployment_id, artifact_id, container_name),
  CONSTRAINT fk_da_dep FOREIGN KEY (deployment_id) REFERENCES deployments(id),
  CONSTRAINT fk_da_art FOREIGN KEY (artifact_id) REFERENCES artifacts(id)
);

-- ===== K8s Inventory =====
CREATE TABLE k8s_resources (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cluster_id BIGINT NOT NULL,
  service_id BIGINT,                       -- NULL before resolution or when resolution fails
  runtime_target_id BIGINT,
  argo_application_id BIGINT,              -- the Argo Application managing this resource (matched by the uid in the resource-tree); NULL when not managed
  resource_uid VARCHAR(64) NOT NULL,
  namespace VARCHAR(128),
  resource_kind VARCHAR(64) NOT NULL,
  resource_name VARCHAR(253) NOT NULL,
  api_version VARCHAR(100),
  restart_count INT NOT NULL DEFAULT 0,
  phase VARCHAR(50),
  argo_health_status VARCHAR(30),          -- Argo's health judgment of this resource (health.status in the resource-tree)
  labels JSONB,
  annotations JSONB,
  spec JSONB,
  status JSONB,
  cmdb_service_code TEXT GENERATED ALWAYS AS (labels ->> 'cmdb.io/service-id') STORED,
  observed_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ,
  CONSTRAINT uk_k8s_uid UNIQUE (cluster_id, resource_uid),
  CONSTRAINT fk_k8s_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(id),
  CONSTRAINT fk_k8s_service FOREIGN KEY (service_id) REFERENCES services(id),
  CONSTRAINT fk_k8s_runtime FOREIGN KEY (runtime_target_id) REFERENCES runtime_targets(id),
  CONSTRAINT fk_k8s_argo FOREIGN KEY (argo_application_id) REFERENCES argo_applications(id)
);
CREATE INDEX idx_k8s_service ON k8s_resources (service_id);
CREATE INDEX idx_k8s_argo_app ON k8s_resources (argo_application_id);
CREATE INDEX idx_k8s_ns_kind ON k8s_resources (cluster_id, namespace, resource_kind);
CREATE INDEX idx_k8s_svc_code ON k8s_resources (cmdb_service_code);

-- History of the key state of a workload resource, kept forever. A row is added only when one of the key fields
-- changes (image digest, replica counts, phase, restart count, terminating); valid_to is NULL for the current state.
-- "State at time T": valid_from <= T AND (valid_to IS NULL OR valid_to > T). Closed when the resource disappears.
CREATE TABLE k8s_resource_history (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  k8s_resource_id BIGINT NOT NULL,
  valid_from TIMESTAMPTZ NOT NULL,
  valid_to TIMESTAMPTZ,
  phase VARCHAR(50),                        -- Pod only
  restart_count INT NOT NULL DEFAULT 0,     -- Pod only: sum over containerStatuses, init containers excluded
  replicas INT,                             -- Deployment / ReplicaSet: spec.replicas
  ready_replicas INT,                       -- Deployment / ReplicaSet: status.readyReplicas
  images JSONB,                             -- [{name,image,digest}] sorted by name; Pod: actual digest, others: spec image only
  terminating BOOLEAN NOT NULL DEFAULT FALSE, -- metadata.deletionTimestamp is set
  CONSTRAINT fk_history_resource FOREIGN KEY (k8s_resource_id) REFERENCES k8s_resources(id),
  CONSTRAINT ck_history_range CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE UNIQUE INDEX uk_history_open ON k8s_resource_history (k8s_resource_id) WHERE valid_to IS NULL;  -- at most one current row
CREATE INDEX idx_history_range ON k8s_resource_history (k8s_resource_id, valid_from DESC);

-- The actual state of each container in a Pod. A Pod can have several containers (including sidecars and init containers),
-- and the image digest and restart reason must be recorded at container level to match back to artifacts and answer "why did which container restart"
CREATE TABLE k8s_pod_containers (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  k8s_resource_id BIGINT NOT NULL,          -- this Pod → k8s_resources
  container_name VARCHAR(253) NOT NULL,
  is_init BOOLEAN NOT NULL DEFAULT FALSE,   -- whether this is an init container
  image VARCHAR(500),                       -- containerStatuses[].image
  image_id VARCHAR(500),                    -- original value of containerStatuses[].imageID
  image_digest VARCHAR(100),                -- sha256:... parsed from imageID, used to match back to artifacts
  ready BOOLEAN,
  restart_count INT NOT NULL DEFAULT 0,
  state VARCHAR(30),                        -- running / waiting / terminated
  state_reason VARCHAR(100),                -- for example CrashLoopBackOff
  last_terminated_reason VARCHAR(100),      -- for example OOMKilled
  last_exit_code INT,
  last_terminated_at TIMESTAMPTZ,
  observed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_pod_container UNIQUE (k8s_resource_id, container_name, is_init),
  CONSTRAINT fk_podc_resource FOREIGN KEY (k8s_resource_id) REFERENCES k8s_resources(id) ON DELETE CASCADE
);
CREATE INDEX idx_podc_digest ON k8s_pod_containers (image_digest);

CREATE TABLE k8s_resource_relationships (
  parent_resource_id BIGINT NOT NULL,
  child_resource_id BIGINT NOT NULL,
  relationship_type VARCHAR(30) NOT NULL,  -- OWNS / SELECTS / REFERENCES
  PRIMARY KEY (parent_resource_id, child_resource_id, relationship_type),
  CONSTRAINT fk_krr_parent FOREIGN KEY (parent_resource_id) REFERENCES k8s_resources(id),
  CONSTRAINT fk_krr_child FOREIGN KEY (child_resource_id) REFERENCES k8s_resources(id)
);
CREATE INDEX idx_krr_child ON k8s_resource_relationships (child_resource_id);

-- K8s Events: the cluster keeps Events for only about 1 hour by default, so they must be landed ourselves to answer "why did it restart"
CREATE TABLE k8s_events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cluster_id BIGINT NOT NULL,
  event_uid VARCHAR(64) NOT NULL,
  namespace VARCHAR(128),
  involved_kind VARCHAR(64),
  involved_name VARCHAR(253),
  involved_uid VARCHAR(64),
  k8s_resource_id BIGINT,
  service_id BIGINT,
  event_type VARCHAR(20),                  -- Normal / Warning
  reason VARCHAR(100),                     -- OOMKilled / BackOff / Unhealthy ...
  message TEXT,
  event_count INT NOT NULL DEFAULT 1,
  first_seen_at TIMESTAMPTZ,
  last_seen_at TIMESTAMPTZ,
  observed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_k8s_event UNIQUE (cluster_id, event_uid),
  CONSTRAINT fk_kev_cluster FOREIGN KEY (cluster_id) REFERENCES clusters(id),
  CONSTRAINT fk_kev_resource FOREIGN KEY (k8s_resource_id) REFERENCES k8s_resources(id),
  CONSTRAINT fk_kev_service FOREIGN KEY (service_id) REFERENCES services(id)
);
CREATE INDEX idx_kev_service_time ON k8s_events (service_id, last_seen_at);
CREATE INDEX idx_kev_involved ON k8s_events (cluster_id, involved_uid, last_seen_at);
CREATE INDEX idx_kev_last_seen ON k8s_events (last_seen_at);

-- ===== Integration Ops =====
-- Written by the Collector, handled by the Processor; doubles as a queue
CREATE TABLE integration_raw_events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider VARCHAR(30) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  external_event_id VARCHAR(200) NOT NULL,           -- polled objects have no source event ID, so the Collector generates one
  ordering_key VARCHAR(300),                         -- for example {cluster}:{uid}; events of the same resource are processed in order
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  process_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',  -- PENDING / PROCESSING / PROCESSED / FAILED / DEAD
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMPTZ,
  last_error TEXT,
  payload JSONB NOT NULL,                             -- redacted
  CONSTRAINT uk_raw_event UNIQUE (provider, external_event_id)   -- idempotency
);
-- Pending / to-be-retried events are usually only a small part of the table, so a partial index keeps it small and fast (work is picked in id order)
-- The Processor's pick-up query must be written as process_status IN ('PENDING', 'FAILED') so the planner can match this index's predicate;
-- measured (20k rows, 5k pending): with a backlog it scans this index in id order; without it, it falls back to scanning the primary key and filtering out processed rows one by one.
CREATE INDEX idx_raw_pending ON integration_raw_events (id)
  WHERE process_status IN ('PENDING', 'FAILED');
CREATE INDEX idx_raw_purge ON integration_raw_events (process_status, processed_at);

CREATE TABLE integration_sync_runs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider VARCHAR(50) NOT NULL,
  sync_type VARCHAR(30) NOT NULL,          -- FULL / INCREMENTAL / POLL / STREAM / WATCH / QUEUE
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  status VARCHAR(30) NOT NULL,
  records_processed INT NOT NULL DEFAULT 0,
  error_message TEXT,
  cursor_state JSONB                       -- for example K8s resourceVersion, the last processed time and continuationToken of ADO polling
);
CREATE INDEX idx_sync_provider_time ON integration_sync_runs (provider, started_at);
CREATE INDEX idx_sync_started ON integration_sync_runs (started_at);  -- purge schedule

-- ===== Platform =====
-- Audit of manual operations, AI tool calls and system writes
CREATE TABLE audit_logs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_type VARCHAR(20) NOT NULL,         -- USER / AI_AGENT / SYSTEM
  actor_id VARCHAR(200) NOT NULL,
  action VARCHAR(100) NOT NULL,            -- for example TOOL_CALL:getDeploymentHistory
  target_type VARCHAR(50),
  target_id VARCHAR(200),
  request JSONB,                            -- redacted
  result_status VARCHAR(30) NOT NULL,
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor_time ON audit_logs (actor_id, created_at);
CREATE INDEX idx_audit_target ON audit_logs (target_type, target_id, created_at);

-- ===== Foreign key indexes =====
-- Postgres does not create indexes on foreign key columns automatically; without an index, deleting / updating a referenced row scans the whole child table and JOINs get slower.
-- Foreign keys already covered by the first column of an existing UNIQUE / composite index are not duplicated. Nullable foreign keys use a partial index to save space.
CREATE INDEX idx_system_team ON systems (owner_team_id) WHERE owner_team_id IS NOT NULL;
CREATE INDEX idx_service_team ON services (owner_team_id) WHERE owner_team_id IS NOT NULL;
CREATE INDEX idx_extres_team ON external_resources (owner_team_id) WHERE owner_team_id IS NOT NULL;
CREATE INDEX idx_sr_repo ON service_repositories (repository_id);
CREATE INDEX idx_extref_service ON external_refs (service_id);
CREATE INDEX idx_pl_service ON pipelines (service_id);
CREATE INDEX idx_pl_repo ON pipelines (repository_id) WHERE repository_id IS NOT NULL;
CREATE INDEX idx_art_build ON artifacts (build_id) WHERE build_id IS NOT NULL;
CREATE INDEX idx_argo_service ON argo_applications (service_id) WHERE service_id IS NOT NULL;
CREATE INDEX idx_argo_runtime ON argo_applications (runtime_target_id) WHERE runtime_target_id IS NOT NULL;
CREATE INDEX idx_da_artifact ON deployment_artifacts (artifact_id);
CREATE INDEX idx_k8s_runtime ON k8s_resources (runtime_target_id) WHERE runtime_target_id IS NOT NULL;
CREATE INDEX idx_kev_resource ON k8s_events (k8s_resource_id) WHERE k8s_resource_id IS NOT NULL;

-- ===== Framework =====
-- Spring Modulith's event publication registry (collector-service uses it with completion-mode=delete,
-- keeping only events whose processing has not completed; the columns match Modulith 2.1.x's JpaEventPublication)
CREATE TABLE event_publication (
  id                     UUID PRIMARY KEY,
  listener_id            VARCHAR(512) NOT NULL,
  event_type             VARCHAR(512) NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMPTZ NOT NULL,
  completion_date        TIMESTAMPTZ,
  status                 VARCHAR(32),
  completion_attempts    INTEGER NOT NULL DEFAULT 0,
  last_resubmission_date TIMESTAMPTZ
);
CREATE INDEX idx_event_publication_incomplete ON event_publication (publication_date)
  WHERE completion_date IS NULL;
