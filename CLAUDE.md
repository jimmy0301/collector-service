# collector-service

CMDB 的擷取與整合服務：**主動往外拉**資料（Azure 無法連入公司網路，不用任何 webhook）、落地為 raw event、再正規化寫入 domain 表。技術：Java 21、Spring Boot 4.1.2、Spring Modulith 2.1.2、jMolecules（DDD + Onion）、Spring Data JPA、PostgreSQL。設計文件在上層目錄：`PLAN.md`、`ARCHITECTURE.md`、`DATA_DICTIONARY.md`、`INTEGRATION_API_SPEC.md`；DDL 在 `src/main/resources/db/migration/V1__init.sql`（Flyway）。

## 建置與測試

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # 預設 java 是 17
cd collector-service   # pom.xml 在這個目錄，不需要上層的 pom
mvn spotless:apply
mvn test     # 單元 + ArchUnit/Modulith + PostgreSQL Testcontainers 的整合測試（需 Docker），全部一起跑
mvn verify   # 同上，再加 spotless 格式檢查與打包
```

整合測試用 Testcontainers 的空 PostgreSQL，schema 由 Flyway 的 `db/migration` 建立（和正式環境同一份），容器整個 JVM 共用一個。ByteBuddy plugin 綁在 `process-classes`，只跑 `mvn compile` 不會轉換 entity。

## 測試技術棧與慣例

- **JUnit 5 + AssertJ + Awaitility**；**Testcontainers**（PostgreSQL 17，容器整個 JVM 共用一個）；**WireMock**（`wiremock-standalone` 3.x，依賴上的 4.0 還是 beta）模擬兩個整合服務。
- `*Test` 是單元測試（不需 Docker）；`*IT` 是整合測試（碰資料庫或啟動應用，需 Docker）。**兩種都由 surefire 執行，`mvn test` 就會全部跑**（沒有使用 failsafe）。只想跑不需要 Docker 的測試時：`mvn test -Dtest='*Test'`。
- 對整合服務的 HTTP 契約測試放在 `src/test/.../<context>/infrastructure/Http*HubTest`，用 WireMock，回應內容放 `src/test/resources/fixtures/`（依 `INTEGRATION_API_SPEC.md` 寫，用 `support/Fixtures` 讀取）。整合服務還不存在，所以這些 fixture 是目前唯一防止「格式理解錯」的依據；服務做出來後要用真實回應核對。
- handler 邏輯（payload → 對 Store 的呼叫）用 `normalization/application/*HandlerTest`：假 Store 在 `RecordingStores`（只記錄呼叫），不啟動 Spring。SQL 規則（較新才覆蓋、刪除標記）由 `Jpa*StoreIT` 負責，兩邊各測一次、不重疊。
- **controller 的測試用 `@WebMvcTest(controllers = X.class)`**（只載入那個 controller 與 web 層，不啟動整個應用，會套用 Boot 的 JSON 與錯誤內容設定），搭配 `@AutoConfigureMockMvc(addFilters = false)` 關掉 security filter，所需的 bean（例如 `CollectionTrigger`、`TokenService`）用 `@TestConfiguration` 提供真的物件、不用 mock。授權則另用 `@WebMvcTest` 匯入該 context 的 `SecurityFilterChain` 設定來測（`*SecurityTest`）。範例：`trigger/infrastructure/TriggerControllerTest`、`TriggerSecurityTest`。
- 只有少數測試啟動整個應用（繼承 `support/PipelineTest`／`PostgresIntegrationTest`，`@SpringBootTest`）：`CollectorEndToEndIT`、`RawEventNormalizerIT`（Modulith 重送與 DEAD 的接線）、`ManagedNamespacesWiringIT`。payload 用 `support/TestPayloads` 產生。
- **測試目錄與 main 的 package 一對一**：`X` 的測試放在同一個 package、叫 `XTest`（單元）或 `XIT`（碰資料庫），例如 `inventory/infrastructure/JpaSiteStoreIT`、`rawevent/application/RawEventRecorderTest`。
- 碰資料庫的類別（`Jpa*Store`、`Jpa*Repository`）用 `support/DatabaseTest`：`@DataJpaTest` slice + Testcontainers PostgreSQL，只載入 JPA 與被測的那個類別（`@Import(JpaSiteStore.class)`），不啟動整個應用；每個測試在交易內執行並 rollback。需要把更新推進資料庫再用 JDBC 驗證時，呼叫 `EntityManager.flush()`／`clear()`。
- 端對端：`CollectorEndToEndIT` 以 WireMock 取代兩個整合服務，依排程的順序直接呼叫 collector，驗證落地 → 轉換 → domain 表；新增來源或事件種類時，補進這個測試的 stub 與斷言。
- **測試金字塔：能用單元測試就用單元測試，不要隨便啟動整個 Spring Boot。** 邏輯（handler、規則、轉換、去重、判斷）寫成純 Java 單元測試（`*Test`，用 `support/Fakes` 的假 Store/Recorder，不需 Spring 與 Docker）。只有「SQL 本身的行為」（`ON CONFLICT`、較新才覆蓋、刪除標記）才需要真的資料庫；整條管線與 `@SpringBootTest` 只留給少數端對端／接線驗證，新增前先確認單元測試真的測不到。
- **每個功能都要附對應的測試**（單元、`*IT`、對外 HTTP 則加 WireMock 契約測試與 fixture），在同一次修改完成並跑 `mvn test`（或 `mvn verify`）。
- 資料隔離：整合測試共用同一個資料庫，各測試實例用隨機後綴（`TestPayloads.run`）避免互相干擾。
- 比較時間欄位時在資料庫裡轉成 UTC，不要在測試裡 `toString()`（JDBC 會用 JVM 預設時區顯示）。

## Bounded Context 與依賴方向

每個 context 是 `io.cmdb.collector.<context>`，根套件有 `package-info.java`（`@BoundedContext`）與 `CLAUDE.md`，內部分 `domain` / `application` / `infrastructure`（ring 以 `package-info.java` 標記）。

| Context | 角色 | 有 aggregate |
|---|---|---|
| `rawevent` | 落地：去敏、去重、`RawEvent`、發布 `RawEventStored` | `RawEvent` |
| `syncrun` | 同步紀錄與資料新鮮度 | `SyncRun` |
| `argo` | Argo 匯集服務的 anti-corruption layer（輪詢） | 無（來源適配） |
| `ado` | Azure DevOps（ADO）Release（CD）的 anti-corruption layer（直接輪詢 REST，增量水位） | 無（來源適配） |
| `k8s` | K8s 整合服務的 anti-corruption layer（輪詢） | 無（來源適配） |
| `normalization` | 監聽 `RawEventStored`，依事件種類正規化 | 無（application） |
| `trigger` | 手動觸發收集的 API（`POST /collect…`，要 collector 簽發、含 `COLLECT` 角色的 JWT；沒設金鑰就關閉） | 無（application） |
| `inventory` | 正規化的寫入端（`*Store`；貧血 `*Entity` 供 validate，原生 upsert 放 Spring Data `@Query`） | 無（之後再 aggregate 化） |

共用的技術模組（不是 context）：`leader`（K8s Lease 的 `LeaderGate`）、`http`（RestClient 工廠）、`security`（預設的 `SecurityFilterChain`：只放行 K8s 探針、Prometheus、`/error`，其餘拒絕；每個對外 API 的 context 用自己的 `securityMatcher` chain 覆蓋自己的路徑；也負責 collector 自己的 JWT 金鑰、`POST /auth/token` 簽發與驗證，見 `security/CLAUDE.md`）。在 `CollectorApplication` 的 `@Modulithic(sharedModules = {"leader", "http"})` 宣告為所有模組都可依賴的共用模組。

```
argo ──▶ rawevent, syncrun, leader          k8s ──▶ rawevent, syncrun, leader
ado ──▶ rawevent, syncrun, leader
trigger ──▶ argo / k8s / ado 的 `application`（NamedInterface）、syncrun 的 `domain`
  └─ ArgoScopeObserved（事件）──────────────▶ k8s（自己維護要查的 namespace）
normalization ──▶ rawevent, inventory, leader        rawevent ──RawEventStored（事件）──▶ normalization
```

跨 context 只透過：模組根套件的公開型別、`@NamedInterface` 的 `domain` / `application` 套件、或事件。不要直接共用別的 context 的狀態。

## 慣例

- **對外 HTTP API 用 OpenAPI contract first**：契約放 `src/main/resources/openapi/<context>.yaml`，用 OpenAPI Generator 產生 server interface（`interfaceOnly`），controller `implements` 它；做法與 Boot 4 注意事項見 `trigger/CLAUDE.md`。

- **Repository 只給 AggregateRoot**（domain 的純 Java 介面；infrastructure 放 `Jpa…Repository` 與 package-private 的 `SpringDataJpa…Repository`）。非 aggregate 的表用 `*Store`。
- **對外 API 的授權用 Spring Security**（不要自己寫攔截器）：新 API 在自己的 context 放一個帶 `securityMatcher` 的 `SecurityFilterChain`，沒被任何 chain 認領的路徑預設被拒絕。
- 同一個實體有兩個以上的唯一鍵（例如 `clusters` 的 `code` 與 `external_id`）時，`ON CONFLICT` 只能指定一個，同時寫入會讓輸家撞到另一個鍵；寫入前先取 `pg_advisory_xact_lock`（見 `JpaClusterStore`）。
- 資料庫產生 id 的 aggregate：欄位用 JPA 的 `@Id @GeneratedValue(IDENTITY)`，jMolecules 的 `@Identity` 標在 `id()` 方法上（標在欄位會被轉成 `@EmbeddedId` 而啟動失敗）。
- 冪等寫入不用 `save()`，用原生 `INSERT … ON CONFLICT`；舊資料不得覆蓋新資料（`observed_at`）。
- 時間欄位 `TIMESTAMPTZ`、程式用 `Instant`；連線 `SET TIME ZONE 'UTC'`。
- Hibernate 的 JSON mapper 已指定 `Jackson3JsonFormatMapper`（`application.yml`）。
- schema 由這個服務的 Flyway 建立與升級（`src/main/resources/db/migration`，V1 = 全部 DDL，啟動時自動套用）；`ddl-auto: validate` 只檢查 entity 與 schema 一致。第一次部署前 V1 可以修改；部署後每個變更都要新增 V2、V3… 檔案，不要改已套用的檔案。連線帳號需有建表權限，或用 `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` 另給只用於 migration 的帳號。

## 尚未做

ADO 的核准與 CI builds、Argo stream / K8s watch、Argo 側的部署對應（`operationState`、history）、`service_id` / `runtime_target_id` 的解析、`cmdb-contract`、`cmdb-service`；`inventory` 之後再改成 aggregate；Lease leader election 與兩個整合服務都沒有對真實環境測過。
