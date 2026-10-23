# 签名迁移设计（P1-4，待 Owner 确认后执行）

> 状态：**仅设计，未执行**。不改现有签名、不删 key、不改 CI 签名配置，直到 Owner 确认。

## 1. 现状

| 项 | 值 |
|---|---|
| 签名材料 | `scripts/elpis-debug.p12.b64`（PKCS12，alias `elpisdebug`） |
| 口令 | 明文写在 `scripts/apply-p1-navigation.sh`（Canary）与 `main` 的 `scripts/apply-patches.sh` |
| 使用者 | Canary（`xyz.lovestyle.home.canary`）与已退役的旧壳（`xyz.lovestyle.home`），两者 `.p12` 文件哈希相同（blob `65792e9b`） |
| 证书主体 | `CN=Elpis Debug, O=HayaGarden, C=CN`，SHA256withRSA，有效期 2026-07-10 → 2053-11-25 |
| **证书 SHA-256** | `78:1E:1A:11:97:F7:81:3B:7F:AA:E6:6E:7A:6C:B7:0A:76:46:22:6B:67:BF:EB:7A:C8:00:79:5D:D4:38:CD:82` |
| 证书 SHA-1 | `74:EB:16:50:E4:37:2C:01:E7:13:62:E9:53:4A:EA:B4:20:46:D4:7D` |

仓库是 public，所以这把 key 已经等同公开：任何人都能用它签一个同包名的 APK。

### 已安装 APK 的指纹

服务器端无法读取手机上的安装包。CI 自 `cursor/stable-debug-signing-dfd5`（2026-08-06）起用这把固定 key 签名，而 Owner 一直可以覆盖安装，说明手机上的 Canary 就是这把 key 签的。核对方法（任选一）：

- 对下载到的 APK：`apksigner verify --print-certs app-debug.apk`，看 `Signer #1 certificate SHA-256 digest` 是否等于上表；
- 手机连电脑：`adb shell pm dump xyz.lovestyle.home.canary | grep -i -A2 signatures`（显示的是截断哈希，只能粗对）。

### 更新链是否依赖这把 key

**是。** Android 只接受与已安装版本同签名（或通过 v3 签名轮换 lineage 证明继承关系）的更新。直接换新 key 打包 → 手机拒绝覆盖安装，只能卸载重装，会丢掉 app 数据（Health 设备绑定凭据、通知去重记录、各项权限授予）。

## 2. 新签名的保存方式

- 在**可信的本地机器**上生成新的 release keystore（RSA 4096 或 EC P-256，有效期 ≥ 25 年），**私钥不进仓库、不进聊天、不进 Agent 会话**。
- 放进 GitHub Actions secrets（仓库 Settings → Secrets and variables → Actions）：
  - `ELPIS_SIGNING_KEYSTORE_B64`（keystore 的 base64）
  - `ELPIS_SIGNING_STORE_PASSWORD`
  - `ELPIS_SIGNING_KEY_ALIAS`
  - `ELPIS_SIGNING_KEY_PASSWORD`
- CI 在 `$RUNNER_TEMP` 里解码，Gradle 从环境变量读；job 结束即丢弃。fork 来的 PR 拿不到 secrets → 只构建不签名（或跳过 artifact 上传）。
- Owner 本地离线备份 keystore + 口令（两处，至少一处离线）。**丢了这把 key 就再也无法升级这个 app。**

## 3. 迁移方案：APK Signature Scheme v3 签名轮换（推荐）

手机是 Android 16，支持 v3/v3.1 轮换（Android 9+ 即可）。轮换能让手机在**不卸载**的情况下从旧 key 迁到新 key，并且可以让旧 key 之后**不再被信任**。

1. **本地生成 lineage**（一次性，需要旧 key 与新 key）：
   ```bash
   apksigner rotate --out elpis.lineage \
     --old-signer --ks elpis-debug.p12 \
     --new-signer --ks elpis-release.jks
   # 取消旧证书的 rollback 能力：之后只用旧 key 签的 APK 不能再覆盖安装
   apksigner lineage --in elpis.lineage --out elpis.lineage \
     --signer --ks elpis-debug.p12 --set-rollback false
   ```
   `elpis.lineage` 不含私钥，可以作为 secret 或普通文件进 CI。
2. **CI 过渡构建**：Gradle 产出未签名 APK 后，用 build-tools 里的 `apksigner` 签：
   ```bash
   apksigner sign --ks old.p12 --next-signer --ks new.jks \
     --lineage elpis.lineage --rotation-min-sdk-version 28 app.apk
   apksigner verify -v --print-certs app.apk   # 应显示 v3 lineage 两张证书
   ```
   过渡期 CI 仍需要旧 key（它本来就已公开，不额外增加风险）。
3. **Owner 装一次过渡包**：覆盖安装应当成功、数据保留。装完用 `apksigner verify` 核对下载的 APK、再看 app 内 build 号。
4. **之后的构建**只用新 key + lineage 签名；从 CI 移除旧 key 与 `apply-p1-navigation.sh` 里的明文口令。
5. **旧壳 `xyz.lovestyle.home`**：已退役。如果手机上还装着，建议直接卸载，而不是为它做迁移。

### 先演练

正式迁移前，用一个临时 appId（例如 `xyz.lovestyle.home.canary.rotationtest`）在同一部手机上完整走一遍 1–4：装旧签名包 → 装过渡包 → 装新签名包 → 再尝试装一个只用旧 key 签的包（应被拒绝）。演练通过再动 Canary。

### 备选：换包名重装

新 appId + 新 key，旧 app 卸载。最简单，但会丢 app 数据、需要重新授权 Health Connect 与通知，不推荐。

## 4. 是否清理仓库历史里的私钥

**安全上没有必要，也做不到真正撤回**：key 已经被公开，任何 clone、fork、缓存都可能保留了它。真正让它失效的是第 3 步的轮换（取消 rollback 能力）。

- **推荐**：轮换完成后，在当前分支删除 `scripts/elpis-debug.p12.b64` 和明文口令（普通提交即可），不改写历史。
- **可选卫生处理**：若仍希望从历史中移除，需在轮换完成后用 `git filter-repo --path scripts/elpis-debug.p12.b64 --invert-paths` 改写**所有分支**并 force-push，然后联系 GitHub Support 清理 PR/缓存引用。代价是所有现存分支、本地 clone、Agent 工作区都要重新同步。属于独立任务，需 Owner 单独批准。
