# ado Context

Azure DevOps（簡稱 **ADO**）**Release（CD）** 的 anti-corruption layer：collector **直接輪詢** ADO REST（vsrm.dev.azure.com，唯讀 PAT；公司網路可連出），把原生 JSON 縮成白名單，落地為 raw event。這個 context 沒有 aggregate。

「部署」的定義（使用者 2026-11-14 決定）＝ **ADO 的一次 CD 部署 ＝ (release, stage, attempt)**，即 `release_deployments`。Argo sync 與叢集端的對應不是這個 context 的事。

## Package 結構

```
ado/                     package-info  @BoundedContext
  domain/                @DomainModelRing  AdoHub（port）
  application/           @ApplicationServiceRing  AdoCollector、AdoPayloads（白名單）
  infrastructure/        @InfrastructureRing  HttpAdoHub、AdoProperties、AdoScheduler
```

## 怎麼輪詢

- `GET {base}/{org}/{project}/_apis/release/deployments?minModifiedTime=…&queryOrder=ascending&$top=…&api-version=7.1`，用 `x-ms-continuationtoken` 標頭翻頁；PAT 以 Basic 認證（空使用者名稱）。
- **增量**：水位（每個 project 一個，只在記憶體）。重啟後從 `now - lookback`（預設 7 天）重來，重複抓取是安全的（見下面的事件 ID）。水位只在「整輪沒有錯誤」後前進，且落後最新修改時間 `overlap`（預設 5 分鐘），避免漏掉晚提交的變更。
- 每個變動的 deployment 另外抓它的 release（`GET …/release/releases/{id}`）；只有該 release 的 deployment 比上次抓時更新才再抓。
- 沒有設定 `organization` 與 `projects`（`ADO_ORGANIZATION`、`ADO_PROJECTS` 以逗號分隔、`ADO_TOKEN`）時什麼都不輪詢。

## 產生的 raw event（provider = `ADO`）

| event_type | external_event_id | ordering_key |
|---|---|---|
| `RELEASE_DEPLOYMENT` | `ado:{project}:deployment:{id}:{lastModifiedOn}` | `ado:{project}:release:{releaseId}` |
| `RELEASE` | `ado:{project}:release:{id}:{modifiedOn}` | 同上（同一個 release 的兩種事件依序處理） |

payload：`{project, fetchedAt, resource}`。**白名單**：release 的 `variables`（非機密的值也可能敏感）、`variableGroups`、各 stage 完整定義一律不存；人只留顯示名稱。

## 對外

Lease 鎖：`cmdb-collector-ado`。同步紀錄 provider：`ADO:{project}`。正規化在 `normalization`（`AdoReleaseHandler`、`AdoReleaseDeploymentHandler`）→ `inventory` 的 `ReleaseStore`。

## 尚未做 / 限制

- 核准（`release_approvals`、Approvals API）、CI builds（所以 `releases.ci_build_id` 目前是 NULL，原值在 `artifact_build_ref`）、pipelines / runtime_targets 的登記（靠人工）。
- 只用 fixture 驗證（`src/test/resources/fixtures/ado/*`，依 Release REST 7.1 文件寫成，**沒有對真實 ADO 回應核對**）；`minModifiedTime` 是否涵蓋核准狀態變化需實測。
- PAT 會到期，需要輪替。
