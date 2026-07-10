# HayaGarden 安卓 App

[English README](./README.md)

这个分支存放的是 HayaGarden 的安卓 App 壳工程。

它是一个 Capacitor 6 Android WebView App。APK 打开后会在原生 Android WebView 里加载生产站 `https://love-style.xyz`。网页 UI 本身由远程服务提供，所以普通页面、路由、API、聊天界面等改动，一般不应该改这个安卓壳仓库，而应该改 VPS 上的前端/网关项目。

## 重要：`android/` 是构建时生成的

`android/` 目录是故意不提交进仓库的。

GitHub Actions 每次构建 APK 时会执行：

```bash
npm install
npx cap add android
bash scripts/apply-patches.sh
cd android && ./gradlew assembleDebug --no-daemon
```

所以，如果仓库里看不到 `android/` 目录，并不代表没有 App 构建；这只是说明原生 Android 工程由 CI 临时生成。

## 这个仓库里有什么

```text
HayaGarden/
├── capacitor.config.json       # Capacitor app id、app 名称、生产 WebView URL
├── package.json                # Capacitor 依赖
├── www/index.html              # 占位页；生产环境由 server.url 覆盖
├── scripts/
│   ├── apply-patches.sh        # 把原生 Android 文件注入到生成出来的 android 工程
│   ├── MainActivity.java       # WebView 行为、原生桥接、返回键处理
│   ├── NativeBridge.java       # 暴露给 WebView 的 JavaScript 原生桥
│   ├── AppTracker.java         # App 使用追踪
│   ├── NotificationWorker.java # 定时轮询本地通知
│   ├── ForegroundService.java  # 前台服务支持
│   └── BootReceiver.java       # 开机广播支持
└── .github/workflows/
    └── build-apk.yml           # Debug APK 构建流水线
```

更详细的工程说明见 [PROJECT.md](./PROJECT.md)。

## 什么情况改这个仓库

下面这些属于安卓原生层，应该改这个仓库：

- WebView 配置
- Android 权限
- 通知逻辑
- 前台服务逻辑
- 开机自启逻辑
- JavaScript 原生桥 API
- App 图标、包名、名称
- APK 构建流程

## 什么情况不要改这个仓库

下面这些通常不属于这个仓库：

- 聊天页布局
- 网站路由
- Flask API
- gateway 行为
- 记忆、中转站、工具逻辑
- VPS 前端服务提供的 CSS/HTML

这些改动通常应该放在服务 `https://love-style.xyz` 的前端/网关项目里。

## APK 构建

构建流水线在 `.github/workflows/build-apk.yml`。

它会在配置的分支 push 后构建 debug APK，也可以手动用 `workflow_dispatch` 触发。构建产物会作为 GitHub Actions artifact 上传，名字类似 `Elpis-debug-<run_number>`。

## 给 Agent 的快速提示

如果你是 AI coding agent：在判断这个仓库有没有安卓 App 构建之前，请先读 [AGENTS.md](./AGENTS.md)。这里没有 `android/` 目录是正常的，不是项目缺失。