# syncrun Context

記錄每個來源每次同步的開始、結束、狀態與游標，供 `cmdb-service` 判斷資料新鮮度（`integration_sync_runs`）。

## Package 結構

```
syncrun/                 package-info  @BoundedContext
  domain/                @DomainModelRing  （@NamedInterface "domain"）
  application/           @ApplicationServiceRing  （@NamedInterface "application"）
  infrastructure/        @InfrastructureRing
```

## Classes

| Class | Ring | 類型 | 說明 |
|---|---|---|---|
| `SyncRun` | domain | AggregateRoot | 生命週期：`start()` → RUNNING → `finish()` → SUCCESS / PARTIAL / FAILED，只能結束一次 |
| `SyncRunRepository` | domain | Repository | `save`、`findById`、`deleteStartedBefore(cutoff, batch)` |
| `SyncStatus`、`SyncType`、`SyncOutcome` | domain | 值 | PARTIAL 表示部分項目失敗，新鮮度判斷不應視為完整成功 |
| `SyncRunTracker` | application | ApplicationService | 包住一次同步：記錄結果並把例外隔離在單一來源內 |
| `SyncRunRetention` | application | 排程 | 每日小批次清除過期紀錄（預設保留 7 天） |
| `JpaSyncRunRepository` | infrastructure | Repository 實作 | 分批刪除用原生子查詢（Postgres 沒有 `DELETE … LIMIT`） |

## 注意

- `provider` 是組合字串：`ARGO:{site}`、`ARGO_CLUSTERS:{site}`、`ARGO_SITES`、`K8S:{site}:{clusterName}`、`K8S_EVENT:…`、`K8S_CLUSTERS:{site}`、`K8S_SITES`；欄位 50 字元，超過截斷。`cmdb-service` 的新鮮度查詢要照這個格式。
