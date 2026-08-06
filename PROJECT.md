# HayaGarden — App Shell Reset P1 (Navigation + Lifecycle Baseline)

## 这是什么

实验分支 `claude/app-shell-reset-p1-navigation`，从 Phase 0 纯壳
`claude/app-shell-reset-pure` 分出。

| | Phase 0 pure | Phase 1B |
|---|---|---|
| appId | `xyz.lovestyle.home.canary` | 同左 |
| Capacitor | 8.5.0 | 同左 |
| server.url | `https://love-style.xyz` | 同左 |
| 自定义原生 | 无 | **仅** Android back（OnBackPressedDispatcher） |
| 旧原生能力 | 禁止 | 继续禁止 |

## 行为

1. WebView → `https://love-style.xyz`
2. Android 返回：有 WebView history → `goBack()`；否则交给系统退出
3. 生命周期：使用 Capacitor `BridgeActivity` 默认（pause/resume **不** reload / 不 reset URL）
4. 外链：Capacitor `Bridge.launchIntent` 默认 —— 同 host 留在 WebView，其它 http(s) → `ACTION_VIEW`

## 构建

```bash
npm ci
npx cap add android   # 或复用已有 android/
npx cap sync android
bash scripts/apply-p1-navigation.sh
cd android && ./gradlew clean assembleDebug
```

`scripts/apply-p1-navigation.sh` 覆盖 `MainActivity.java`，并把 CI/debug APK
绑定到仓库内固定的 `elpis-debug.p12`（`scripts/elpis-debug.p12.b64`），避免
runner 默认 debug 证书每次不同导致无法覆盖安装。不是旧的 `apply-patches.sh`。

## 明确不包含

ForegroundService、NotificationWorker、Pocket*、AppTracker、ScreenCapture、
NativeBridge / ElpisNative、BootReceiver、FCM、textZoom、captureInput、
keyboard/viewport hacks、自定义 WebViewClient。
