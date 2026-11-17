# k8s Context

K8s 整合服務（各廠區叢集）的 **anti-corruption layer**：輪詢、縮減內容、落地為 raw event。沒有 aggregate：domain 只有 port（`K8sHub`）與來源回報的形狀（`Cluster`、`ResourceKind`、`Site`）。

## Package 結構

```
k8s/                     package-info  @BoundedContext
  domain/                @DomainModelRing  K8sHub（port）、Cluster、ResourceKind、Site
  application/           @ApplicationServiceRing  K8sCollector、K8sPayloads、ManagedNamespaces
  infrastructure/        @InfrastructureRing  HttpK8sHub（分頁、410 Gone 重來）、K8sProperties、K8sScheduler
```

## 產生的 raw event（provider = `K8S`）

| event_type | external_event_id | 說明 |
|---|---|---|
| `Deployment`、`ReplicaSet` | `k8s:{clusterId}:{uid}:{resourceVersion}` | 完整保留 spec / status（去敏後） |
| `Pod` | 同上 | 只保留 metadata 與 `phase`、`containerStatuses`、`initContainerStatuses`（`imageID` 是關聯的橋樑） |
| `Event` | 同上 | 只查 `type=Warning`，叢集內只留約 1 小時所以 30 秒輪詢一次 |
| `CLUSTER_SNAPSHOT`、`SITE_SNAPSHOT` | 指紋 | 叢集清單、廠區 |

## 對外與依賴

- 訂閱 `argo.ArgoScopeObserved`，由 `ManagedNamespaces` 維護要查詢的 namespace（同步的 `@EventListener`，只在記憶體）。範圍是空的叢集會被跳過，不產生「成功」的 sync run。
- Lease 鎖：`cmdb-collector-k8s`。
