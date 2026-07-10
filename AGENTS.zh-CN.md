# Agent 说明

[English version](./AGENTS.md)

这个仓库分支**确实有安卓 App 构建**。

不要因为仓库里没有提交 `android/` 目录，就判断 App 不存在。这是一个 Capacitor Android WebView 壳。原生 Android 工程会在 CI 里通过 `npx cap add android` 生成，然后由 `scripts/apply-patches.sh` 注入原生代码和配置。

## 优先阅读这些文件

在判断工程结构前，请先读：

1. `README.md` - 简短入口说明
2. `PROJECT.md` - 详细工程指南
3. `capacitor.config.json` - app id、app 名称、WebView 目标 URL
4. `.github/workflows/build-apk.yml` - APK 构建流水线
5. `scripts/apply-patches.sh` - 原生 Java 文件如何注入到生成出来的 Android 工程

## 工程模型

这个分支不是网页前端源码，而是包住生产站的 Android 原生壳。

- 原生壳仓库：当前分支，`Hayana11/HayaGarden`
- 运行时网站：`https://love-style.xyz`
- Web UI / API / gateway 代码：在 VPS 前端/网关项目里，不在这个 Android 壳分支里

APK 使用 Capacitor，并在 `capacitor.config.json` 里配置了 `server.url`，所以 App 会加载远程生产站，而不是把完整网页 UI 打包进 APK。

## 为什么没有 `android/` 目录

`android/` 是故意不提交的。

CI 构建流程：

```bash
npm install
npx cap add android
bash scripts/apply-patches.sh
cd android && ./gradlew assembleDebug --no-daemon
```

`scripts/apply-patches.sh` 会把 `scripts/*.java` 复制到生成出来的 Android 源码目录，并修改生成出来的 Android manifest / build 文件。

## 什么情况改这个仓库

当需求涉及这些内容时，应该改这个分支：

- Android 权限
- WebView 设置
- 暴露给 JavaScript 的原生桥 API
- 返回键行为
- 本地通知
- 前台服务
- 开机广播接收器
- 截屏 / Android 原生 API
- App 图标、app id、app 名称
- GitHub Actions APK 构建行为

## 什么情况不要改这个仓库

普通网页产品需求不要改这个分支，例如：

- 聊天页渲染
- 网站导航或路由
- Flask / gateway API 行为
- 中转站 / provider 逻辑
- 记忆系统行为
- workspace 工具
- VPS Web App 提供的 CSS 或 HTML

这些应该改服务端前端/网关项目。

## 常见误判

错误结论：

> 仓库里没有 `android/`，所以没有安卓 App。

正确结论：

> 这是一个 Capacitor Android App。Android 工程由 CI 生成，原生定制代码在 `scripts/` 和 `apply-patches.sh` 里。

## 构建产物

Debug APK 由 `.github/workflows/build-apk.yml` 构建，并作为 GitHub Actions artifact 上传，名字类似 `Elpis-debug-<run_number>`。

## 如果要接 Pocket-Browser

如果要接入 Pocket-Browser 或类似“手机浏览器”能力，优先复用现有 Capacitor/WebView 壳；除非用户明确要求单独 App，否则不要新建一个互不相关的安卓工程。