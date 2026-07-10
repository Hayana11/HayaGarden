# Agent Notes

[中文版本](./AGENTS.zh-CN.md)

This repository branch **does have an Android app build**.

Do not conclude that the app is missing because there is no committed `android/` directory. This is a Capacitor Android WebView shell. The native Android project is generated during CI with `npx cap add android`, then patched by `scripts/apply-patches.sh`.

## First Files To Read

Read these before making architecture claims:

1. `README.md` - short human-facing orientation
2. `PROJECT.md` - detailed project guide
3. `capacitor.config.json` - app id, app name, WebView target URL
4. `.github/workflows/build-apk.yml` - APK build workflow
5. `scripts/apply-patches.sh` - how native Java files are injected into the generated Android project

## Project Model

This branch is not the web frontend source. It is the Android native shell around the production site.

- Native shell repo: this branch, `Hayana11/HayaGarden`
- Runtime website: `https://love-style.xyz`
- Web UI/API/gateway code: lives in the VPS frontend/gateway project, not in this Android shell branch

The APK uses Capacitor with `server.url` in `capacitor.config.json`, so the app loads the remote production site rather than bundling the full web UI into the APK.

## Why `android/` Is Missing

`android/` is intentionally not committed.

CI build flow:

```bash
npm install
npx cap add android
bash scripts/apply-patches.sh
cd android && ./gradlew assembleDebug --no-daemon
```

`scripts/apply-patches.sh` copies files from `scripts/*.java` into the generated Android source tree and edits the generated Android manifest/build files.

## Edit This Repo For

Make changes here when the request involves:

- Android permissions
- WebView settings
- native bridge APIs exposed to JavaScript
- back button behavior
- local notifications
- foreground services
- boot receivers
- screen capture / native Android APIs
- app icon, app id, app name
- GitHub Actions APK build behavior

## Do Not Edit This Repo For

Do not use this branch for ordinary web product work such as:

- chat page rendering
- website navigation or routes
- Flask/gateway API behavior
- relay/provider logic
- memory system behavior
- workspace tools
- CSS or HTML served by the VPS web app

Those belong in the server-side frontend/gateway project.

## Common Mistake To Avoid

Wrong conclusion:

> There is no Android app because this repo has no `android/` directory.

Correct conclusion:

> This is a Capacitor Android app. The Android project is generated in CI, and the native customizations live in `scripts/` plus `apply-patches.sh`.

## Build Artifact

The debug APK is produced by `.github/workflows/build-apk.yml` and uploaded as a GitHub Actions artifact named like `Elpis-debug-<run_number>`.

## If You Add Pocket-Browser Support

If adding Pocket-Browser or similar phone-browser features, prefer integrating with the existing Capacitor/WebView shell instead of creating a second unrelated Android app unless the user explicitly asks for a separate app.