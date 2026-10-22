# AGENTS.md — HayaGarden (Canary branch)

`HayaGarden` on branch `claude/app-shell-reset-pure` is a **pure Capacitor 8 Android WebView
canary** (app id `xyz.lovestyle.home.canary`). It loads `https://love-style.xyz` and must not
include any former native patches (push, geo, screenshot, bridges, etc.).

Technical docs: `PROJECT.md`. The `CLAUDE.md` is persona roleplay, not technical docs.

## Cursor Cloud specific instructions

- Deliverable is a debug APK. Web product lives in `hayagarden-frontend` (do not modify from here).
- `npm ci` then `npx cap add android` + `npx cap sync android` + `./gradlew assembleDebug`.
- There is **no** `scripts/apply-patches.sh` on this branch — do not restore old native injection.
- Android SDK may need to be installed locally for assembleDebug; CI builds APK artifacts.
