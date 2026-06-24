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
│   └── NotificationWorker.java # WorkManager 15min 轮询，拉新消息推送本地通知
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
2. AndroidManifest 追加 `PACKAGE_USAGE_STATS`、`POST_NOTIFICATIONS` 权限
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

## 已有的原生功能

| 功能 | 文件 | 说明 |
|------|------|------|
| 返回键 | `MainActivity.java` | WebView 内先后退，到根页面才退 app |
| 字体缩放 | `MainActivity.java` | `setTextZoom(100)` 屏蔽系统字体大小影响 |
| App 使用追踪 | `AppTracker.java` | 每 60s 读 UsageStats，POST 到 `/api/activity` |
| 推送通知 | `NotificationWorker.java` | WorkManager 每 15min 轮询，有新消息弹本地通知 |

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
