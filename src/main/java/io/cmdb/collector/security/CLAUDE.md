# security（共用的技術模組）

不是 bounded context。放整個服務共用的安全設定：預設的 filter chain、collector 自己簽發與驗證 JWT 的金鑰與 API。每個對外 API 的 context 用自己的 `securityMatcher` chain 管自己的路徑（例如 `trigger` 的 `/collect/**`）。

## Package 結構

```
security/
  domain/          TokenIssuer（port）、IssuedToken、TokenPolicy、InvalidTokenRequestException
  application/     TokenService（依 policy 檢查請求後簽發）
  infrastructure/  PemKeys、JwtProperties、JwtConfig、NimbusTokenIssuer、CollectorJwtDecoder、
                   TokenController、TokenSecurityConfig、DefaultSecurityConfig、StaticTokenIntrospector；
                   另有產生的 AuthApi 與 *Dto（契約：src/main/resources/openapi/auth.yaml）
```

部署步驟（產生金鑰、K8s Secret 與 Deployment 範例、部署後驗證、更換金鑰、疑難排解）見模組根目錄的 `JWT_DEPLOYMENT.md`。

## 流程

1. 管理者用 **admin token**（`COLLECTOR_ADMIN_TOKEN`）呼叫 `POST /auth/token`，填 `subject`、`roles`、`expiresInSeconds`（可省略）。
2. collector 檢查 policy（角色必須在 `allowed-roles`、有效期不得超過 `max-lifetime`），用 **signing key** 以 RS256 簽發；若有設定 **encryption key**，再把簽好的 token 加密（JWE：RSA-OAEP-256 + A256GCM，內容類型 JWT）。
3. 呼叫端帶 `Authorization: Bearer <token>` 呼叫 `/collect/**`；collector 用同一組金鑰驗證（有加密就先解密再驗簽章；驗 issuer 與有效期）。token 的 `roles` 對應成 `ROLE_*`，`/collect/**` 要求 `ROLE_COLLECT`。

`COLLECTOR_JWT_ENABLED` 不是 true 時，token API 與 collect API 都回 404；沒有 admin token 時 token API 回 404。

```bash
curl -X POST -H "Authorization: Bearer $COLLECTOR_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"subject":"ops-pipeline","roles":["COLLECT"],"expiresInSeconds":3600}' http://collector:8080/auth/token
```

## 金鑰

RSA，至少 2048 位元，PEM 檔（私鑰 PKCS#8、公鑰 X.509），通常由 K8s Secret 掛載。用 openssl 產生：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out sign.key
openssl pkey -in sign.key -pubout -out sign.pub
# 要加密 token 時另外產生一組
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out enc.key
openssl pkey -in enc.key -pubout -out enc.pub
```

| 設定（環境變數） | 說明 |
|---|---|
| `collector.security.admin-token`（`COLLECTOR_ADMIN_TOKEN`） | 可以要求簽發的 token；沒設就關閉簽發 |
| `collector.security.jwt.enabled`（`COLLECTOR_JWT_ENABLED`，預設 false） | **總開關**。false 時簽發 API 與 `/collect` 都回 404；true 時下面的簽章金鑰必填，缺了啟動失敗 |
| `…jwt.signing-private-key`、`…signing-public-key`（`COLLECTOR_JWT_SIGNING_PRIVATE_KEY`、`COLLECTOR_JWT_SIGNING_PUBLIC_KEY`，值如 `file:/keys/sign.key`） | 簽章金鑰；`enabled` 為 true 時兩個都要設 |
| `…jwt.encryption-private-key`、`…encryption-public-key`（`COLLECTOR_JWT_ENCRYPTION_PRIVATE_KEY`、`…_PUBLIC_KEY`） | 選用；兩個都設才加密，只設一個啟動失敗；設了以後只接受加密過的 token |
| `…jwt.issuer`（`collector-service`）、`key-id`（`collector-1`）、`default-lifetime`（1h）、`max-lifetime`（24h）、`allowed-roles`（`COLLECT`） | 簽發的限制，`application.yml` 裡都寫出來了 |

`application.yml` 把每個金鑰設定對到環境變數，預設是空字串（視為沒設定）。

啟動時會檢查：檔案能讀、PEM 格式、至少 2048 位元，以及私鑰與公鑰是不是一對（簽章：私鑰簽、公鑰驗；加密：公鑰加密、私鑰解密），不符就啟動失敗。

私鑰是機密：不要放進映像、環境變數的內容或日誌；PKCS#1（`BEGIN RSA PRIVATE KEY`）格式會被拒絕並提示用 `openssl pkcs8 -topk8 -nocrypt` 轉換。

## 預設的 chain（DefaultSecurityConfig）

沒被任何 context 的 chain 認領的請求：只放行 `/actuator/health/**`（K8s 探針）、`/actuator/info`、`/actuator/prometheus`、`/error`（handler 丟出 404、409 時容器轉到這裡，擋掉會變 403），其餘一律拒絕。新增端點預設是關閉的。

## 注意

- 金鑰輪替：目前只有一組金鑰（`kid` 固定），換金鑰會讓舊 token 全部失效；要平滑輪替需要 JWKS 與多把金鑰，還沒做。
- 沒有撤銷機制：token 在到期前一直有效，所以有效期要短（預設 1 小時、最長 24 小時）。
- admin token 等於可以簽發任何允許的角色，等同 root，要妥善保管。
