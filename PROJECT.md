# HayaGarden — App Shell Reset P2A (NativeBridge Lite)

## 这是什么

实验分支 `claude/app-shell-p2a-nativebridge-lite`，从 Phase 1B
`claude/app-shell-reset-p1-navigation` 分出（stacked on Draft PR #14）。

| | Phase 1B | Phase 2A |
|---|---|---|
| appId | `xyz.lovestyle.home.canary` | 同左 |
| Capacitor | 8.5.0 | 同左 |
| server.url | `https://love-style.xyz` | 同左 |
| 自定义原生 | Android back only | back + **NativeBridge Lite** |
| JS 桥 | 无 | `window.ElpisNative` 仅 6 方法 |

## 行为

1. WebView → `https://love-style.xyz`
2. Android 返回：有 WebView history → `goBack()`；否则交给系统退出（P1B 保留）
3. 生命周期：Capacitor 默认（pause/resume **不** reload）
4. `window.ElpisNative`（仅主动 JS 调用，启动零副作用）：
   - `getBattery()` → JSON（percent / charging / chargeType / tempC）
   - `getScreenTime()` → JSON（totalMinutes + apps Top10）或 error
   - `hasUsageAccess()` → boolean
   - `openUsageAccessSettings()` → 打开系统 Usage Access（仅用户主动）
   - `isIgnoringBatteryOptimizations()` → boolean
   - `requestIgnoreBatteryOptimizations()` → 请求电池白名单（仅用户主动）

## 构建

```bash
npm ci
npx cap add android
npx cap sync android
bash scripts/apply-p2a-nativebridge-lite.sh   # 内部先跑 P1B
bash scripts/test-p2a-nativebridge-lite.sh
cd android && ./gradlew clean assembleDebug
```

`apply-p2a-nativebridge-lite.sh` 会：
1. 调用 `apply-p1-navigation.sh`（MainActivity + stable debug signing）
2. 复制 `native/NativeBridge.java` 到 canary 包路径
3. 幂等追加 `PACKAGE_USAGE_STATS` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

## 明确不包含

ForegroundService、NotificationWorker、AppTracker、ScreenCapture*、Pocket*、
BootReceiver、GPS、POST_NOTIFICATIONS、自动权限弹窗、后台轮询、前端 UI 改动。
