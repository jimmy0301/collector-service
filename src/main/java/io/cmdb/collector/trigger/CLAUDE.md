# trigger Context

讓人（或 AI／排程工具）**立刻**要求 collector 去來源拉一次資料，不用等排程。collector 唯一的對外業務 API（另外只有 actuator）。這個 context 沒有 aggregate、也不存資料；真正的拉取、落地、記錄 `integration_sync_runs` 都是原本的 collector 在做。

## 契約（OpenAPI，contract first）

API 的定義在 `src/main/resources/openapi/trigger.yaml`，**改 API 先改這個檔**。OpenAPI Generator（`openapi-generator-maven-plugin`，`generate-sources` 階段）依它產生到 `target/generated-sources/openapi`：

- `CollectApi`（server interface，`interfaceOnly`）：`TriggerController implements CollectApi`，路徑、參數、回傳型別不符就編譯失敗。
- `TriggerReportResponse`、`JobReportResponse`、`SyncStatusResponse`：回應的 DTO（`modelNameSuffix=Response`，與 domain 的 `TriggerReport`、`JobReport` 區分；controller 負責轉換）。

產生的類別放在 `trigger.infrastructure` 套件，不要手改；改了 yaml 後 `mvn generate-sources`（或任何 `mvn compile`）就會重新產生。Boot 4 / Jackson 3 的設定取捨：不產生 swagger 註解、不啟用 bean validation、不用 jackson-databind-nullable（都是 Jackson 2 或這裡用不到）；產生的 model 仍會用 `@NotNull`，所以只加了 `jakarta.validation-api`（沒有 validator）。

## 認證與授權（Spring Security + collector 自己簽發的 JWT）

- `TriggerSecurityConfig` 是 `/collect/**` 專屬的 `SecurityFilterChain`（`securityMatcher`，`@Order(1)`）：要求 collector 自己簽發的 JWT（RS256，有設加密金鑰時還加密）且 `roles` 含 `COLLECT`。沒帶、錯誤、過期、被竄改回 401 與 `WWW-Authenticate: Bearer`；token 有效但沒有 `COLLECT` 回 403。**`collector.security.jwt.enabled` 不是 true 時整個 API 關閉**，任何請求都回 404。
- token 由 `POST /auth/token` 簽發（用 admin token，填 subject、roles、有效期），金鑰、設定與流程見 `security/CLAUDE.md`。
- 其餘請求由 `security` 模組的預設 chain 拒絕（只放行 K8s 探針、Prometheus、`/error`），所以新增端點預設是關閉的。
- 之後若改用公司的身分系統發行 token：把 `security` 的 `JwtDecoder` 換成設了 `issuer-uri` 或 `jwk-set-uri` 的 decoder（發行者在 `roles` claim 放 `COLLECT`），`/collect/**` 的規則與 controller 不用改。
- yaml 只宣告 `bearerAuth` security scheme，不在契約裡實作驗證。

## Package 結構

```
trigger/                 package-info  @BoundedContext
  domain/                @DomainModelRing  CollectionJob（port）、JobReport、TriggerReport、UnknownJobException、TriggerBusyException
  application/           @ApplicationServiceRing  CollectionTrigger（執行、單來源一次一個）
  infrastructure/        @InfrastructureRing  TriggerController、TriggerSecurityConfig、CollectionJobs（把 collector 接成 job）；另有產生的 CollectApi 與 *Response
```

## API

驗證：`Authorization: Bearer <JWT>`（先用 `POST /auth/token` 取得，見下方「認證與授權」）。**沒設 token 金鑰時所有端點回 404**（等於關閉）；token 錯、過期或沒帶回 401，沒有 `COLLECT` 角色回 403。請求會等收集結束才回應。

| 方法與路徑 | 作用 |
|---|---|
| `GET /collect` | 列出 source 與 job |
| `POST /collect` | 依序執行 argo → k8s → ado 全部 job |
| `POST /collect/{source}` | 執行該 source 全部 job（`argo`、`k8s`、`ado`） |
| `POST /collect/{source}/{job}` | 執行單一 job |

Job：`argo`：`sites`、`clusters`、`applications`；`k8s`：`sites`、`clusters`、`workloads`、`events`；`ado`：`releases`。

回應（200）：`{"source":"argo","jobs":[{"job":"applications","status":"PARTIAL","runs":2,"durationMs":812,"errors":["ARGO:PLANT-A: resource-tree shop: 502 upstream down"]}]}`

- `status` 是該 job 追蹤到的所有 sync run 中最差的（FAILED > PARTIAL > SUCCESS）。`runs` 是實際跑了幾次 sync run；**0 代表沒有東西可收**（沒設定 ADO 專案、K8s 沒有 Argo 涵蓋的 namespace），不代表成功收了資料。細節在 `integration_sync_runs`。
- `errors` 說明沒成功的原因：每個 PARTIAL／FAILED 的 sync run 一筆，格式 `provider: 原因`（和 `integration_sync_runs.error_message` 同一段文字，最多 20 筆、每筆最多 500 字）；job 自己丟出例外時（例如整合服務連不上以致連 sync run 都建不起來）則是那個例外。全部成功時是空陣列。其他 job 照常執行。錯誤文字可能含內部網址或整合服務回的訊息，所以這個 API 只給內部使用、要帶 token。
- 404：source／job 不存在，或功能未啟用；401：token 錯誤；409：同一個 source 已經有一個觸發在執行（稍後再試）。

## 設計

- 同一個 source 同時只讓一個觸發執行（`tryLock`）；觸發與排程重疊是安全的，因為落地以 external event id 去重、collector 的記憶體狀態是 `ConcurrentHashMap`。
- 任何副本都能執行（不要求是 leader），因為多個副本同時接收負載平衡的請求；落地是冪等的。
- 只應在叢集內部暴露（ClusterIP），不要放到外部 Ingress；token 是最低限度的保護，不是完整的授權。

## 新增 job

在 `CollectionJobs` 加一個 `@Bean @Order(n) CollectionJob`（`CollectionJob.of(source, name, collector::method)`）；collector 的方法要回傳 `CollectionResult`（`tracker.track` 的結果逐次 `with(...)`）。順序即「全部執行」的順序。
