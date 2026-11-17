# normalization Context

監聽 `RawEventStored`，依事件種類（provider + event_type）呼叫 handler，把 raw event 正規化寫入 `inventory`。

## Package 結構

```
normalization/           package-info  @BoundedContext
  application/           @ApplicationServiceRing  RawEventNormalizer、*Handler、KeyedLocks、ImageReference、Payloads
  infrastructure/        @InfrastructureRing  NormalizationScheduler（leader 定期重送）
```

## 流程

`RawEventStored` → `@ApplicationModuleListener`（非同步、新交易、commit 後；Modulith event publication registry 持久化）→ 以 `ordering_key` 取分段鎖 → handler → 同交易 `markProcessed`。

- 失敗：`RawEventLifecycle.recordFailure`（新交易）記 FAILED 與 attempt_count，**重新丟出例外**讓 publication 保持未完成；`NormalizationScheduler`（持有 Lease `cmdb-collector-normalize`）每分鐘重送。達 `max-attempts`（預設 5）標 DEAD 並吞掉例外。DEAD 事件在交易內的部分寫入不會 rollback。
- 沒有 handler 的事件種類直接略過，raw event 維持 PENDING；之後有了 handler，`PendingEventSweeper`（leader 每 5 分鐘、只送超過 10 分鐘的 PENDING）會把它們送進來，不需要手動處理。

## Handlers

| Handler | 事件 | 寫入 |
|---|---|---|
| `SiteSnapshotHandler` | ARGO / K8S `SITE_SNAPSHOT` | `sites` |
| `K8sClusterSnapshotHandler` | K8S `CLUSTER_SNAPSHOT` | `sites`（確保存在）、`clusters` |
| `ArgoClusterSnapshotHandler` | ARGO `CLUSTER_SNAPSHOT` | `clusters.argo_cluster_name`（以 server 比對，對不到不算失敗） |
| `K8sResourceHandler` | K8S Deployment / ReplicaSet / Pod | `k8s_resources`、`k8s_resource_relationships`（ownerReferences）、`k8s_pod_containers`、`artifacts`（Pod 的映像 digest 首次出現時） |

## 新增 handler

1. 實作 `RawEventHandler`（`supports` 與 `handle`；必須冪等，重送會重複執行）。
2. 缺必要欄位就丟例外（用 `Payloads.required`），事件會被標為失敗並留下原因。
3. 加 handler 的單元測試（`*HandlerTest`，假 Store 在 `RecordingStores`）；SQL 規則放在 `inventory` 的 `Jpa*StoreIT`。不要為了一個 handler 啟動整個應用。
4. 新增 handler 前就已落地的 PENDING 事件會由 `PendingEventSweeper` 自動送進來（要確認 handler 對舊格式的 payload 也能處理，否則會失敗並在 5 次後變成 DEAD）。
- `K8sResourceHandler` 每次觀測都呼叫 `K8sResourceHistoryStore`，狀態由 `K8sResourceStates.of(kind, object)` 取出；只有這些欄位進歷史：Pod 的 phase / 重啟總數 / 各 container 的 digest、Deployment 與 ReplicaSet 的 replicas / readyReplicas / spec 的映像、是否有 deletionTimestamp。labels、resourceVersion、conditions 這類常變的欄位不進，否則每次輪詢都會新增一列。
- **Argo**：`ArgoApplicationHandler`（`APPLICATION_SNAPSHOT` → `argo_applications`：只留每個 source 的 repoURL / path / targetRevision / chart / ref / name，不存 helm values；`spec.syncPolicy.automated` 存在即 `auto_sync`；叢集由 destination 的 server 或名稱在同一廠區內比對）與 `ArgoResourceTreeHandler`（`RESOURCE_TREE_SNAPSHOT` → `k8s_resources.argo_application_id`、`argo_health_status`，只處理 `nodes[]`，`orphanedNodes[]` 不是 Application 管理的所以不連）。tree 事件帶 `application:{uid,name}`，因為 tree 本身不說它屬於哪個 Application。Argo 與 K8s 的事件順序不固定，所以 tree 先到時會為還沒收到的 Deployment / ReplicaSet / Pod 建占位列（K8s 事件之後覆蓋、不動 Argo 欄位）。兩個叢集清單 handler 在更新後呼叫 `linkUnmappedClusters(site)`，補上較晚才登記的叢集。
- **Argo 的 sync operation → `deployments`**：`ArgoApplicationHandler` 同時從 `status.operationState` 取出最近一次 operation（`ArgoOperations.of`），經 `DeploymentStore.recordArgoOperation` 寫入。冪等鍵 `{application uid}:{startedAt}`；phase 對應：Running／Terminating → RUNNING、Succeeded → SUCCEEDED、Failed／Error → FAILED，**未知的 phase 會讓事件失敗而不是猜**。`trigger_type` 先固定 `ARGOCD`（還沒對 release）。依 INTEGRATION_API_SPEC 的欄位對應表做，release 對應、`build_id`、`application_revision` 都還沒做。
