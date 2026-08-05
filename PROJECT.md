# HayaGarden — App Shell Reset P0 (Pure WebView Canary)

## 这是什么

**实验分支** `claude/app-shell-reset-pure`：最小化 Capacitor Android WebView 壳。

唯一行为：启动后加载 `https://love-style.xyz`。

用于验证页面变形、点击失效、IME/光标异常是否来自旧原生壳。**不是**正式 App，**不**迁入旧原生能力。

| | 正式 `main` | 本 canary |
|---|---|---|
| appId | `xyz.lovestyle.home` | `xyz.lovestyle.home.canary` |
| appName | Elpis | Elpis Canary |
| Capacitor | 6 + 大量 patch | 8.5.0 官方生成壳 |
| 原生能力 | 推送/GPS/截屏/桥等 | **无** |

---

## 目录

```
HayaGarden/
├── capacitor.config.json   # appId canary + server.url
├── package.json            # @capacitor/* 8.5.0
├── www/index.html          # 离线兜底占位
├── assets/icon.png         # 可选图标
└── .github/workflows/build-apk.yml
```

`android/` 由 CI / 本地 `npx cap add android` 生成，不入库。

**已删除**：`scripts/*.java`、`apply-patches.sh`、以及所有旧原生注入。

---

## 本地构建

```bash
npm ci
npx cap add android
npx cap sync android
cd android && ./gradlew clean assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

需要 Android SDK + JDK（Capacitor 8 推荐 JDK 21）。

---

## CI

push / PR 到本分支相关路径时构建 debug APK，artifact：

`Elpis-Canary-pure-webview-<run_number>`

不签正式包，不用正式 keystore。

---

## 明确不包含

ForegroundService、NotificationWorker、WorkManager 自定义轮询、FCM、Local Notifications、
Pocket*、AppTracker、UsageStats、GPS、ScreenCapture / MediaProjection、BootReceiver、
NativeBridge / `window.ElpisNative`、Doze 白名单、开机自启、启动权限申请、
`setTextZoom`、自定义 JS interface、`captureInput`、任何 WebView/键盘/viewport hack。
