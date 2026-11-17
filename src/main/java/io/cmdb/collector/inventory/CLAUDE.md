# inventory Context

正規化的**寫入端**：依來源快照 upsert `sites`、`clusters`、`k8s_resources`、`k8s_pod_containers`、`k8s_resource_relationships`、`artifacts`、`k8s_events`。

目前**不是** DDD 意義的 aggregate：這些表只有 upsert、沒有領域行為，所以介面叫 `*Store`（不叫 Repository）。六張表各有一個貧血的 `*Entity`（無行為、`@Immutable`），讓 `ddl-auto=validate` 啟動時涵蓋它們；寫入仍是原生 upsert（「較新才覆蓋」無法用 find + save 原子地做），放在 package-private 的 Spring Data 介面。之後 `cmdb-service` 要讀取、決定 `cmdb-contract` 時，再改成 aggregate（`Site`、`Cluster`、`K8sResource` + `PodContainer` entity、`Artifact`）。

## Package 結構

```
inventory/               package-info  @BoundedContext
  domain/                依資源分子 package，每個子 package 各有 package-info：@DomainModelRing 與 @NamedInterface("domain.<名稱>")（Modulith 的 named interface 只對一個 package 有效、不含子 package，所以每個都要標；新增子 package 時別漏）
    site/                SiteSnapshot、SiteStore
    cluster/             ClusterSnapshot、ClusterStore
    k8s/                 K8sResourceSnapshot、PodContainerSnapshot、K8sResourceStore、K8sEventSnapshot、K8sEventStore、K8sResourceState、K8sResourceHistoryStore
    artifact/            ArtifactStore
    argo/                ArgoApplicationSnapshot、ArgoResourceNode、ArgoApplicationStore
    deployment/          DeploymentStore、ArgoOperationSnapshot（deployments 表）
    release/             ReleaseSnapshot、ReleaseDeploymentSnapshot、ReleaseStore
  infrastructure/        @InfrastructureRing  *Entity（六張表的資料列對應，@Immutable、只為 validate 與唯讀查詢）、
                         SpringData*Repository（package-private，原生 ON CONFLICT upsert 的 @Query）、Jpa*Store（實作 port）
```

## 規則（在 SQL 裡）

- `k8s_resources`、`k8s_pod_containers`：只有 `observed_at` 不早於已存值才覆蓋（舊事件不得覆蓋新狀態）；條件不成立時沒有 `RETURNING` 的列，再 `SELECT` 補 id。
- 父資源尚未處理時 `ensureStub` 先建佔位列（`observed_at` = epoch），父資源自己的事件之後覆蓋。
- `clusters.code` 自動建立時預設 `{site}-{name}`（小寫），之後可人工改；upsert 不覆蓋 code、environment。
- `sites` 的 name、region 為 null 時不覆蓋既有值。
- **資料永久保留，來源消失也不刪除**：`k8s_resources` 用 `deleted_at` 標記。collector 在某 namespace 某種資源的 list（含所有分頁）成功後發出 `RESOURCE_LIST`（此刻存在的 uid），`K8sResourceListHandler` 把「資料庫有、list 沒有、`observed_at <= fetchedAt`、尚未標記」的列標上 `deleted_at`。list 中途失敗不發事件（不能據此判斷消失）；同一內容每小時重發一次，讓處理順序造成的遺漏自我修正。已知限制：被標記後若 uid 又出現卻沒有新的 resourceVersion，不會被清除標記。
- **`k8s_events`**：每個 event uid 一列（K8s 會就地更新 Event 的 count / lastTimestamp，所以後來的版本更新同一列，`observed_at` 較新才覆蓋），永久保留。`k8s_resource_id` 與 `service_id` 在寫入時依 involved uid 對 `k8s_resources` 解析；當時資源還不存在就為 NULL，之後的更新才會補上（已知限制：Event 不再更新就不會補）。
- **`k8s_resource_history`**（狀態歷史，永久保留）：`K8sResourceHistoryStore.record(resourceId, state, observedAt)`。只在關鍵欄位變化（phase、重啟次數、副本數、映像 digest、是否正在終止）時關閉目前那列（`valid_to`）並新增一列；相同狀態不新增，較舊的觀測或落在已關閉區間內的觀測會被忽略（歷史不改寫）。兩段 SQL（先關閉、再新增）而不是一個 CTE，因為「每個資源最多一列目前狀態」的部分唯一索引會在舊列關閉前就檢查。資源被標記刪除時（`markMissingDeleted`）目前那列在同一時間關閉。查某時間點：`valid_from <= T AND (valid_to IS NULL OR valid_to > T)`。已知限制：只看得到被觀測到的狀態，兩次輪詢之間的變化看不到。
- **`releases`、`release_deployments`**（ADO CD）：`ReleaseStore`。`releases` 以 (project, release id) 為鍵，`modified_at` 較新才覆蓋、null 不洗掉既有值；`pipeline_id` 在寫入時由已登記的 release definition 解析，沒登記就是 NULL。部署事件可能比 release 事件先到，所以先 `ensureStub`，之後被真實資料覆蓋。`release_deployments` 以 (release, stage 實例, attempt) 為鍵，`last_modified_at` 較新才覆蓋；`runtime_target_id` 只在該 stage 剛好對到一個 runtime target 時才填。
- **`argo_applications`** 與 `k8s_resources` 的 Argo 欄位：`ArgoApplicationStore`。Application 以 `external_uid` 為鍵，`observed_at` 較新才覆蓋；`cluster_id` 在寫入時於同一廠區內以 destination 的 server（忽略大小寫與結尾斜線）或名稱比對，比對不到保留原值，之後可由 `linkUnmappedClusters` 補。`linkResources` 把 tree 的 `nodes[]` 寫成 `argo_application_id` 與 `argo_health_status`：Application 的叢集已知時，缺的 Deployment / ReplicaSet / Pod 建占位列（其他種類不建，因為 K8s 沒有收集）；叢集未知時只更新已存在、以 uid 比對到的列。只寫會變的列。
- **`deployments`**（Argo 端的事實）：`DeploymentStore.recordArgoOperation`。以 `external_operation_key` 冪等；RUNNING 在還是 RUNNING 時會更新，SUCCEEDED／FAILED 是最終事實、不再改變（SUPERSEDED 只會被較晚才看到的結束狀態取代）。同一個 Application 有較新的 operation 時，較舊且仍 RUNNING 的標為 SUPERSEDED（Argo 只保留最近一次 operation，舊的結束事件可能永遠看不到）。PLAN 4.5 的規則是「同一 `runtime_target`」，因為 runtime_target 還沒登記，這裡先用 Application 當範圍。`service_id`、`runtime_target_id` 從 Application 複製（有才有）。
