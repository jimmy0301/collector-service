# JWT 部署說明

collector 自己簽發與驗證 JWT：用來保護 `POST /collect…`（手動觸發收集）。這份文件說明怎麼產生金鑰、放進 K8s、啟用、簽發第一個 token、確認可用，以及出問題時怎麼查。設計背景見知識庫的 ADR 025，程式與設定細節見 `src/main/java/io/cmdb/collector/security/CLAUDE.md`。

## 運作方式

```mermaid
sequenceDiagram
    autonumber
    participant Admin as 管理者
    participant C as collector
    participant Caller as 呼叫端（腳本、排程工具）
    Admin->>C: POST /auth/token（admin token；subject、roles、有效期）
    C->>C: 檢查角色與有效期，用簽章私鑰簽發（有設加密金鑰再加密）
    C-->>Admin: JWT
    Admin->>Caller: 交付 JWT
    Caller->>C: POST /collect/...（Authorization: Bearer JWT）
    C->>C: 解密、用簽章公鑰驗證、檢查 issuer、有效期與 COLLECT 角色
    C-->>Caller: 收集結果
```

要有三樣東西才能運作：**簽章金鑰對**（必要）、**admin token**（簽發用）、**加密金鑰對**（選用）。`COLLECTOR_JWT_ENABLED` 不是 `true` 時，簽發 API 與 `/collect` 都回 404，其他功能不受影響。

## 1. 產生金鑰

在一台可信任的機器上執行（不要在 collector 的容器裡）。需要 OpenSSL。

```bash
# 簽章金鑰對（必要）
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out sign.key
openssl pkey -in sign.key -pubout -out sign.pub

# 加密金鑰對（選用；要加密 token 才需要）
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out enc.key
openssl pkey -in enc.key -pubout -out enc.pub

# admin token（簽發 token 用的密碼）
openssl rand -base64 32
```

- 私鑰第一行應該是 `-----BEGIN PRIVATE KEY-----`（PKCS#8）。如果是 `BEGIN RSA PRIVATE KEY`（PKCS#1），collector 會拒絕；用 `openssl pkcs8 -topk8 -nocrypt -in old.key -out sign.key` 轉換。
- 長度至少 2048 位元，更短的會拒絕。
- 簽章與加密要用**兩組不同的金鑰**，不要共用。
- 私鑰與 admin token 是機密：不要放進 git、映像、聊天或工單，產生後立刻存進 Secret，並刪除本機檔案或交由密碼管理工具保管。

## 2. 放進 K8s Secret

```bash
kubectl create secret generic collector-jwt-keys -n <namespace> \
  --from-file=sign.key --from-file=sign.pub \
  --from-file=enc.key  --from-file=enc.pub        # 不加密就省略後兩行

kubectl create secret generic collector-admin-token -n <namespace> \
  --from-literal=token='<上面產生的 admin token>'
```

在 Deployment 掛載並設定環境變數（片段）：

```yaml
spec:
  template:
    spec:
      securityContext:
        fsGroup: 10001                  # 讓應用程式的使用者讀得到掛載的檔案（依實際使用者調整）
      containers:
        - name: collector
          env:
            - name: COLLECTOR_JWT_ENABLED
              value: "true"
            - name: COLLECTOR_JWT_SIGNING_PRIVATE_KEY
              value: file:/keys/sign.key
            - name: COLLECTOR_JWT_SIGNING_PUBLIC_KEY
              value: file:/keys/sign.pub
            - name: COLLECTOR_JWT_ENCRYPTION_PRIVATE_KEY   # 不加密就刪掉這兩個
              value: file:/keys/enc.key
            - name: COLLECTOR_JWT_ENCRYPTION_PUBLIC_KEY
              value: file:/keys/enc.pub
            - name: COLLECTOR_ADMIN_TOKEN
              valueFrom:
                secretKeyRef:
                  name: collector-admin-token
                  key: token
          volumeMounts:
            - name: jwt-keys
              mountPath: /keys
              readOnly: true
      volumes:
        - name: jwt-keys
          secret:
            secretName: collector-jwt-keys
            defaultMode: 0440
```

- **每個副本都要掛載同一組金鑰**，這樣任何副本簽發的 token 都能被其他副本驗證。
- 其他可調整的設定（有預設值，可以不設）：

| 環境變數 | 預設 | 說明 |
|---|---|---|
| `COLLECTOR_JWT_ENABLED` | `false` | 總開關 |
| 簽發的限制：`COLLECTOR_SECURITY_JWT_ISSUER`、`…_KEY_ID`、`…_DEFAULT_LIFETIME`、`…_MAX_LIFETIME`、`…_ALLOWED_ROLES` | `collector-service`、`collector-1`、`1h`、`24h`、`COLLECT` | 對應 `application.yml` 的 `collector.security.jwt` 下的同名設定（Spring 的環境變數命名規則）；`ALLOWED_ROLES` 用逗號分隔 |

## 3. 網路：只在叢集內部開放

簽發 API 與收集 API 都只該在叢集內部使用：

- Service 用 `ClusterIP`，**不要**透過外部 Ingress 或 LoadBalancer 暴露 `/auth` 與 `/collect`。
- 建議加 NetworkPolicy，只允許需要呼叫它的 Pod 或命名空間連入。
- K8s 探針與 Prometheus 使用的 `/actuator/health/**`、`/actuator/prometheus` 不需要 token，這是預期的。

## 4. 部署後確認

先確認啟動成功（缺金鑰、格式錯誤會讓應用程式啟動失敗，見最下面的錯誤表）：

```bash
kubectl rollout status deploy/collector -n <namespace>
kubectl logs deploy/collector -n <namespace> | grep -i "jwt\|Started CollectorApplication"
```

接著從叢集內部（例如 `kubectl exec` 進同命名空間的 Pod，或 `kubectl port-forward`）測試完整流程：

```bash
ADMIN='<admin token>'
BASE=http://collector:8080          # port-forward 時用 http://localhost:8080

# 沒帶 admin token 應該是 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/auth/token

# 簽發 token（可省略 expiresInSeconds，預設 1 小時，最長 24 小時）
TOKEN=$(curl -s -X POST $BASE/auth/token \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"subject":"ops-pipeline","roles":["COLLECT"],"expiresInSeconds":3600}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

# 用 token 列出可觸發的工作，回 200 就代表簽發與驗證的金鑰成對、設定正確
curl -s -H "Authorization: Bearer $TOKEN" $BASE/collect

# 觸發一個工作
curl -s -X POST -H "Authorization: Bearer $TOKEN" $BASE/collect/argo/sites
```

collector 啟動時會檢查金鑰：檔案能讀、是 PEM、至少 2048 位元，而且**私鑰與公鑰是一對**（用私鑰簽一段資料、再用公鑰驗證；加密金鑰則用公鑰加密、私鑰解密）。任何一項不符，應用程式就會啟動失敗並說明是哪一組，不會帶著錯誤的金鑰上線。上面的流程仍建議做一次，確認簽發與呼叫整條路徑都通。

## 5. 日常操作

- **發 token 給新的呼叫端**：用 admin token 呼叫 `POST /auth/token`，`subject` 寫呼叫端的名稱（方便稽核），`roles` 用 `["COLLECT"]`，有效期盡量短。把 token 交給對方（不要寫進 git），並在 Secret 或密碼管理工具裡保管。
- **token 過期**：重新簽發一個。沒有 refresh 機制。
- **查看 token 內容**：沒有加密時，token 的第二段是 base64 的 JSON（可用 `cut -d. -f2 | base64 -d` 查看）；有加密時看不到內容，這是預期的。
- **撤銷 token**：目前**沒有撤銷機制**，token 在到期前一直有效。如果有 token 外洩，只能更換金鑰（見下），所以有效期要短。

## 6. 更換金鑰

目前一次只支援一組金鑰（沒有 `kid` 輪替與 JWKS），換金鑰會讓**所有舊 token 立刻失效**：

1. 產生新的金鑰對，更新 Secret（`kubectl create secret … --dry-run=client -o yaml | kubectl apply -f -`）。
2. 重啟所有副本（`kubectl rollout restart deploy/collector`）。滾動更新期間新舊副本的金鑰不同，這段時間簽發與驗證可能互相失敗（401），建議用 `strategy: Recreate`，或在低峰時段進行。
3. 重新簽發所有呼叫端要用的 token。
4. 換 admin token 的做法相同：更新 Secret 並重啟，舊的 admin token 立刻失效。

需要不中斷的輪替時，要先實作 JWKS 與多把金鑰（尚未開發）。

## 7. 出問題時

### 啟動失敗

| 訊息 | 原因與處理 |
|---|---|
| `collector.security.jwt.enabled is true, so signing-private-key and signing-public-key are required` | 開了開關但沒給簽章金鑰路徑；檢查環境變數名稱與值 |
| `set both … encryption-public-key and encryption-private-key, or neither` | 加密金鑰只設了一個；兩個都設或都不設 |
| `cannot read key file: …` | 路徑錯誤、檔案不存在或沒有讀取權限；檢查掛載路徑與 `fsGroup`、`defaultMode` |
| `expected a PEM block "-----BEGIN PRIVATE KEY-----" …` | 檔案不是 PEM、或放反了（私鑰與公鑰互換）；如果提到 PKCS#1，照訊息用 `openssl pkcs8` 轉換 |
| `RSA key is shorter than 2048 bits` | 金鑰太短，重新產生 |
| `collector.security.jwt.signing-*: the private key and the public key are not a pair …` | 簽章的私鑰與公鑰不是同一對（常見是換了私鑰沒換公鑰，或放錯 Secret 的檔案）；用 `openssl pkey -in sign.key -pubout` 重新導出公鑰 |
| `collector.security.jwt.encryption-*: the private key and the public key are not a pair …` | 加密金鑰同上 |
| `not an RSA PKCS#8 private key` / `not an RSA public key` | 不是 RSA 金鑰（例如用了 EC 金鑰），重新用 RSA 產生 |

### API 回應

| 狀態碼 | 常見原因 |
|---|---|
| 404 | 功能沒開啟：`COLLECTOR_JWT_ENABLED` 不是 `true`（回應沒有內容）；簽發 API 另外在沒設 `COLLECTOR_ADMIN_TOKEN` 時也是 404；`/collect` 在來源或工作名稱不存在時也是 404（有 `detail`，這是和「功能沒開啟」區分的方法） |
| 401 | 沒帶 token、token 過期、被竄改、簽章金鑰不符（例如 token 是用別的金鑰簽的）、issuer 不符、加密設定不一致（開了加密卻拿到未加密的 token，或反過來）、用 admin token 呼叫 `/collect`（admin token 只能簽發 token） |
| 403 | token 有效但沒有 `COLLECT` 角色；或呼叫了沒開放的路徑（預設一律拒絕） |
| 400 | 簽發請求不符 policy：subject 空白、沒有 roles、角色不在 `allowed-roles`、有效期不是正數或超過最大值；回應是 JSON，原因在 `detail` 欄位 |
| 409 | 同一個來源已經有一個觸發在執行，稍後再試（`detail` 有說明） |

排查 401 時，先確認：拿剛簽發的 token 立刻呼叫 `GET /collect`。如果立刻就 401，通常是加密設定不一致或 token 沒帶對；如果是過一陣子才 401，是 token 過期。伺服器端的日誌不會印出 token 內容。

## 8. 安全檢查清單

- [ ] 私鑰與 admin token 只存在 K8s Secret（或公司的密碼管理系統），沒有進 git、映像、日誌或文件。
- [ ] 能讀取 Secret 的人受到 RBAC 限制（私鑰外洩就能偽造任何 token）。
- [ ] `/auth`、`/collect` 沒有經過外部 Ingress，NetworkPolicy 限制了來源。
- [ ] 每個副本掛載同一組金鑰。
- [ ] token 的有效期夠短，沒有被拿來長期使用；`max-lifetime` 沒有被調得過大。
- [ ] `allowed-roles` 只有需要的角色。
- [ ] 完成第 4 節的驗證（簽發後立刻呼叫 `GET /collect` 回 200）。
- [ ] 已規劃金鑰外洩時的處理：更換金鑰並重啟（第 6 節）。
