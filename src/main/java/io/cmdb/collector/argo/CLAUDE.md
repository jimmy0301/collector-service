# argo Context

Argo 匯集服務（各廠區的 Argo CD）的 **anti-corruption layer**：輪詢、把原生 JSON 縮成要保存的內容，落地為 raw event。這個 context 沒有 aggregate：domain 只有取得資料的 port 與來源回報的形狀。

## Package 結構

```
argo/                    package-info  @BoundedContext；公開 API：Destination、ArgoScopeObserved
  domain/                @DomainModelRing  ArgoHub（port）、Site
  application/           @ApplicationServiceRing  ArgoCollector、ArgoPayloads
  infrastructure/        @InfrastructureRing  HttpArgoHub、ArgoProperties、ArgoScheduler
```

## 產生的 raw event（provider = `ARGO`）

| event_type | external_event_id | 說明 |
|---|---|---|
| `APPLICATION_SNAPSHOT` | `argo:{site}:app:{uid}:{內容指紋}` | 排除每輪都變的欄位（resourceVersion、reconciledAt、observedAt）與完整資源清單 |
| `RESOURCE_TREE_SNAPSHOT` | `argo:{site}:tree:{uid}:{指紋}:{yyyyMMddHH}` | Application 有變化立即取，另按 `tree-interval` 補償；payload 另帶 `application:{uid,name}`；每小時即使沒變也重新落地一次（Application 的叢集較晚才登記時，靠它補連結） |
| `CLUSTER_SNAPSHOT` | `argo:{site}:clusters:{指紋}:{yyyyMMddHH}` | 只留 name、server（cluster 物件含連線設定）；每小時重新產生一次 |
| `SITE_SNAPSHOT` | `argo:sites:{指紋}` | 廠區代碼、名稱、區域 |

## 對外

- 發布 `ArgoScopeObserved(site, destinations)`（排除 app-of-apps）；`k8s` context 據此決定要查哪些 namespace。
- Lease 鎖：`cmdb-collector-argo`。
