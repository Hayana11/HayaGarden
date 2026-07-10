# HayaGarden APK — 新窗口上手指南

## 这是什么

Capacitor 6 Android WebView 壳。APK 本身不含任何页面内容——打开就加载 `https://love-style.xyz`（生产站），所有 UI 变动直接改 VPS 的 Flask 服务即可，不需要重打 APK。

只有改 **原生行为**（通知、返回键、app 使用追踪、WebView 配置等）才需要动这个 repo 并触发新构建。

---

## 仓库 & branch

- GitHub: `hayana11/hayagarden`
- 工作 branch: `claude/frontend-project-setup-cq7k3s`
- APK 构建：push 到 `main` 或 `claude/**` 分支后 GitHub Actions 自动构建，产物在 Actions artifact（`VII-debug-<run_number>`，保留 30 天）

---

## 目录结构

```
HayaGarden/
├── capacitor.config.json      # 核心配置：server.url=https://love-style.xyz
├── package.json               # deps: @capacitor/core @capacitor/android @capacitor/cli v6
├── www/index.html             # 占位页（实际不用，server.url 覆盖了 webDir）
├── scripts/
│   ├── apply-patches.sh       # CI 构建时把下面三个 Java 文件注入到 android/ 目录
│   ├── MainActivity.java      # 自定义主活动：返回键逻辑 + WebView 字体缩放 fix
│   ├── AppTracker.java        # 60s 轮询 UsageStatsManager，上报前台 app 到后端
│   ├── ForegroundService.java # 常驻前台服务：GPS/电量/屏幕时间上报 + 推送长轮询
│   ├── NotificationWorker.java # WorkManager 15min 轮询，长轮询被杀时的兜底
│   ├── ScreenCaptureService.java            # MediaProjection 常驻截屏，按需抓帧上传
│   ├── ScreenCapturePermissionActivity.java # 透明活动，弹系统投屏授权框
│   ├── NativeBridge.java      # JS ↔ 原生桥（电量/屏幕时间/截屏/权限）
│   └── BootReceiver.java      # 开机自启 ForegroundService
└── .github/workflows/
    └── build-apk.yml          # CI 流水线（见下）
```

`android/` 目录不存在于 repo 中——CI 每次从 `npx cap add android` 生成，再由 `apply-patches.sh` 注入原生代码。

---

## CI 构建流程

```
npm install
npx cap add android          # 生成 android/ 目录
bash scripts/apply-patches.sh  # 注入 Java + 修改 AndroidManifest + build.gradle
cd android && ./gradlew assembleDebug
```

apply-patches.sh 做了什么：
1. 把 `scripts/*.java` 复制进 `android/app/src/main/java/xyz/lovestyle/home/`
2. AndroidManifest：
   - 一次性追加权限块（`PACKAGE_USAGE_STATS`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE` 及其
     Android 14 细分类型 `_DATA_SYNC`/`_MEDIA_PROJECTION`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、
     位置权限、`RECEIVE_BOOT_COMPLETED`）
   - 一次性追加组件声明（`ForegroundService`/`ScreenCaptureService`/`ScreenCapturePermissionActivity`/`BootReceiver`）
   - ⚠️ 权限和组件都用**单条 sed 整块插入**，不要再用多条 `sed /pattern/a` 串接——那样后一条会匹配到
     前一条刚插入的行，导致位置权限等被重复插入多遍（历史坑）
3. `build.gradle` 追加 WorkManager 依赖 `androidx.work:work-runtime:2.9.0`

---

## capacitor.config.json 关键字段

```json
{
  "appId": "xyz.lovestyle.home",
  "appName": "VII",
  "server": { "url": "https://love-style.xyz" },   ← 指向生产站，覆盖 webDir
  "android": {
    "captureInput": true,                           ← 输入法兼容
    "webContentsDebuggingEnabled": true             ← Chrome DevTools 可远程调试
  }
}
```

---

## ⚠️ WebView 兼容性红线（2026-07-05 排查结论）

手机是华为 CDY-AN95（Android 10，无 Google Play），**系统 WebView 永远停在 Chrome 78**，不会更新。
因此 VPS 前端（/opt/frontend/static/*.html、*.js）必须遵守：

- **JS 语法上限 ES2019**：禁止可选链 `?.`、空值合并 `??`、逻辑赋值 `||=`/`&&=`/`??=`、`replaceAll`、`Promise.any`、`.at()`、`crypto.randomUUID` 等 Chrome 79+ 特性。一个 `?.` 会让整个 `<script>` 块解析失败、页面完全瘫痪（board 就是这么死的：7/1 三栏重构引入 `?.`，app 里 board 自此不发任何 API 请求）
- **CSS 注意**：flex 容器的 `gap` Chrome 84+ 才支持（78 里被忽略、元素挤在一起）；`clamp()`/`min()`/`max()` 是 79+
- **排错通道**：app 内 WebView 开不了 DevTools。board/calendar 的 `<head>` 里装了 `window.onerror` 上报（POST `/api/client-error`），报错落在 VPS `/opt/frontend/client_errors.log`，含 UA/页面/行号。新页面出问题先把这段 beacon 复制进去
- **验证方法**：`npx acorn --ecma2019` 解析所有内联 script（或用 node+acorn `{ecmaVersion:2019}` 扫一遍），过不了的就是 app 里的死亡脚本
- 改 static 资源后**必须 bump sw.js 的 CACHE 版本**（家规，否则 app 里 Service Worker 供旧文件）

---

## 已有的原生功能

| 功能 | 文件 | 说明 |
|------|------|------|
| 返回键 | `MainActivity.java` | WebView 内先后退，到根页面才退 app |
| 字体缩放 | `MainActivity.java` | `setTextZoom(90)` 屏蔽系统字体大小影响 |
| App 使用追踪 | `AppTracker.java` | 每 60s 读 UsageStats，上报前台 app |
| 屏幕时间/电量/GPS | `ForegroundService.java` | 常驻前台服务每 10min 上报 |
| 推送通知（秒达） | `ForegroundService.java` | 常驻 HTTP 长轮询，后端一发消息立即弹 |
| 推送通知（兜底） | `NotificationWorker.java` | WorkManager 15min 轮询，长轮询被杀时补网 |
| 截屏 | `ScreenCaptureService.java` | MediaProjection 常驻，收到 `screenshot` 命令或 JS 调用即抓帧上传；**非静默**：常驻"共享中·可关闭"通知 + 每次截屏弹"看了你屏幕一眼·HH:mm" |
| 后台保活 | `ForegroundService` + `NativeBridge` | 前台服务 + Doze 白名单 + 开机自启 + onTaskRemoved 重启 |
| Pocket 远程眼 | `PocketClient.java` | OkHttp WebSocket 连 `wss://love-style.xyz/pocket/ws`，5 指令 + 指数退避重连 |
| JS 桥 | `NativeBridge.java` | `window.ElpisNative.*`（见下方 JS API） |

### `window.ElpisNative` JS API（WebView 内可直接调用）

| 方法 | 返回 | 说明 |
|------|------|------|
| `getBattery()` | JSON string | 电量/充电状态/温度 |
| `getScreenTime()` | JSON string | 今日各 app 前台时长 Top10 + 总分钟 |
| `takeScreenshot()` | void | 立即截屏上传；未授权则自动拉起投屏授权框 |
| `requestScreenCapturePermission()` | void | 手动拉起投屏授权框 |
| `isScreenCaptureReady()` | boolean | 投屏授权是否已就绪（=共享是否开启中） |
| `stopScreenCapture()` | void | 停止屏幕共享，释放投屏 |
| `hasUsageAccess()` | boolean | 是否已授予"使用情况访问" |
| `openUsageAccessSettings()` | void | 打开 UsageStats 授权设置页 |
| `isIgnoringBatteryOptimizations()` | boolean | 是否已在电池优化白名单 |
| `requestIgnoreBatteryOptimizations()` | void | 请求加入电池优化白名单 |

> 调用前判空：`if (window.ElpisNative) { ... }`（浏览器里不存在该对象）。

---

## ⚠️ 后端需要提供/调整的接口（VPS 侧，不在本 repo）

新原生功能依赖以下后端契约，改这个 repo 前先确认 VPS 已就绪：

1. **推送长轮询** `GET /api/wake_log/pending_notification?wait=<秒>&since=<上次id>`
   - `wait`：后端最多挂起这么多秒等新消息（长轮询）。**不支持也没关系**——立即返回即可，
     客户端会自己每 3s 兜底重试；但支持 `wait` 才能真正做到"前端一发、手机秒弹"。
   - 返回 JSON：`{"has_message":bool, "id":"<唯一id>", "title":"...", "content":"...", "command":"screenshot"|""}`
   - `id` 用于客户端去重（长轮询 + WorkManager 双通道共用），务必对每条消息唯一且稳定。
   - `command="screenshot"` 会触发客户端静默截屏并上传（见下）。
2. **截屏上传** `POST /api/screenshot/upload`
   - body 为 `image/jpeg` 原始字节，header `X-Capture-Ts` 是毫秒时间戳。
   - 后端存盘即可，返回 2xx。
3. 已有：`POST /api/geo/report`、`POST /api/device/report`（含 `screen_today_minutes`）、
   `GET /api/dream/events`（AppTracker 上报）。
4. **pocket-browser**（P1）：手机连 `wss://love-style.xyz/pocket/ws?token=...`。
   token 与 VPS `/opt/pocket/.env` 的 `POCKET_TOKEN` 相同。
   App 内写入：`adb shell run-as xyz.lovestyle.home` 或 SharedPreferences `elpis_pocket`：
   - `pocket_token` — 必填
   - `pocket_ws` — 可选，默认 `wss://love-style.xyz/pocket/ws`

---

## 什么情况改这里 vs 改 VPS

| 需求 | 改哪里 |
|------|--------|
| 页面 UI、新路由、API | VPS `/opt/frontend/` (Flask app) — 无需重打 APK |
| 通知逻辑、轮询间隔 | `scripts/NotificationWorker.java` → commit → CI 自动构建 |
| 返回键/WebView 行为 | `scripts/MainActivity.java` |
| 新原生权限或依赖 | `scripts/apply-patches.sh` |
| app 包名/名称/图标 | `capacitor.config.json` + CI 相关步骤 |

---

## VPS 侧参考（不在本 repo，仅供定向）

- Flask app: `/opt/frontend/` — 服务跑在 5050 端口，`systemctl status frontend`
- 内存系统: `/opt/ombre-brain/` — MCP server，port 8000
- gateway: `/opt/frontend/gateway.py` — 5051 端口，AI 对话主逻辑
- DB: `/opt/frontend/memories.db`
- 工具脚本: `/opt/frontend/tools/` — cc_board_check.py / patrol.py / dream_wake.py 等
