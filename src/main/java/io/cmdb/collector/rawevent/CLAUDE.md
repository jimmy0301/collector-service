# rawevent Context

把外部來源的資料「落地」：去敏、去重、寫入 `integration_raw_events`，並發布 `RawEventStored` 觸發正規化。raw event 是稽核與狀態紀錄（PENDING → PROCESSED / FAILED → DEAD），不是 queue。

## Package 結構

```
rawevent/                package-info  @BoundedContext
  domain/                @DomainModelRing  （@NamedInterface "domain"）
  application/           @ApplicationServiceRing  （@NamedInterface "application"）
  infrastructure/        @InfrastructureRing
```

## Classes

| Class | Ring | 類型 | 說明 |
|---|---|---|---|
| `RawEvent` | domain | AggregateRoot | provider、eventType、externalEventId、orderingKey、payload（`JsonNode`，JSONB）；建構時驗證長度與必填 |
| `RawEventRepository` | domain | Repository | `saveIfAbsent`（回傳新 id 或 empty）、`findById`、`markProcessed`、`recordFailure`、`markDead` |
| `RawEventStored` | domain | DomainEvent | 只帶 id、provider、eventType、orderingKey（不帶 payload） |
| `PayloadRedactor` | domain | 領域邏輯 | 去敏：env 值、envFrom、managedFields、last-applied、Helm values、token / password 類字串 |
| `ContentFingerprint` | domain | 領域邏輯 | 與欄位順序無關的 JSON 指紋，輪詢物件用來組冪等的 external_event_id |
| `RawEventRecorder` | application | ApplicationService | `@Transactional record()`：記憶體 LRU 去重 → 去敏 → INSERT → 發布事件（同一交易，transactional outbox） |
| `RawEventLifecycle` | application | ApplicationService | `markProcessed`（同交易）、`recordFailure`（`REQUIRES_NEW`，達上限標 DEAD） |
| `JpaRawEventRepository` | infrastructure | Repository 實作 | 冪等寫入是 `INSERT … ON CONFLICT DO NOTHING RETURNING id`（原生查詢，非 `@Modifying`） |

## 注意

- `record()` 必須在交易內，否則 `@TransactionalEventListener` 不會觸發。
- 只對應 Collector 會寫的欄位；狀態、時間由資料庫預設值與正規化更新。
