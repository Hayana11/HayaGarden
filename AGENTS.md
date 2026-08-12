# AGENTS.md — HayaGarden (P2A NativeBridge Lite canary)

Branch `claude/app-shell-p2a-nativebridge-lite` is a **Capacitor 8 Canary WebView**
with P1B Android back handling plus **NativeBridge Lite** (`window.ElpisNative`,
six explicit JS methods only). Injected via `scripts/apply-p2a-nativebridge-lite.sh`
(composes `scripts/apply-p1-navigation.sh`).

Do **not** restore Phase-0-forbidden native capabilities (services, trackers,
capture, Pocket, GPS, notification workers). Do **not** merge/deploy from agent
automation unless the user explicitly requests it for a later phase.
Do **not** modify Draft PR #14 or the production appId `xyz.lovestyle.home`.

## Build

```bash
npm ci && npx cap add android && npx cap sync android
bash scripts/apply-p2a-nativebridge-lite.sh
bash scripts/test-p2a-nativebridge-lite.sh
cd android && ./gradlew clean assembleDebug
```
