# AGENTS.md — HayaGarden

`HayaGarden` is a **Capacitor 6 Android WebView shell** (app id `xyz.lovestyle.home`). It has no UI of
its own — it loads the production website (`server.url` in `capacitor.config.json`) and adds native
device behaviors (push, geo, screenshot, back button) that are injected at build time by
`scripts/apply-patches.sh`. Technical docs: `PROJECT.md`. The `CLAUDE.md` is persona roleplay, not
technical docs.

## Cursor Cloud specific instructions

- **There is no dev server / runtime service to "run"** — the deliverable is an APK. The web product
  it wraps lives in the `hayagarden-frontend` repo.
- Node deps install with `npm install` (Capacitor CLI works: `npx cap --version`). The environment
  update script runs `npm install` here.
- **Building the APK requires the Android SDK** (`gradlew assembleDebug` after `npx cap add android`
  + `bash scripts/apply-patches.sh`). The Android SDK is **not installed** in this environment; the
  APK is normally produced by CI (`.github/workflows/build-apk.yml` on push to `main`/`claude/**`).
  Only install the Android SDK locally if you specifically need to build the APK on the VM.
