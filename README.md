# HayaGarden Android App

[中文说明](./README.zh-CN.md)

This branch contains the Android app shell for HayaGarden.

It is a Capacitor 6 Android WebView app. The APK opens the production site at `https://love-style.xyz` inside a native Android WebView. The web UI itself is served remotely, so normal page, route, API, and chat UI changes usually belong in the VPS frontend project, not in this Android shell repository.

## Important: `android/` Is Generated

The `android/` directory is intentionally not committed.

GitHub Actions creates it during each APK build with:

```bash
npm install
npx cap add android
bash scripts/apply-patches.sh
cd android && ./gradlew assembleDebug --no-daemon
```

If you do not see an `android/` directory in the repository, that does **not** mean the app build is missing. It means the native Android project is generated in CI.

## What This Repo Contains

```text
HayaGarden/
├── capacitor.config.json       # Capacitor app id, app name, and production WebView URL
├── package.json                # Capacitor dependencies
├── www/index.html              # Placeholder page; server.url overrides this in production
├── scripts/
│   ├── apply-patches.sh        # Injects native Android files into the generated android project
│   ├── MainActivity.java       # WebView behavior, native bridge setup, back button handling
│   ├── NativeBridge.java       # JavaScript bridge exposed to the WebView
│   ├── AppTracker.java         # App usage tracking
│   ├── NotificationWorker.java # Periodic local notification polling
│   ├── ForegroundService.java  # Foreground service support
│   └── BootReceiver.java       # Boot receiver support
└── .github/workflows/
    └── build-apk.yml           # Debug APK build workflow
```

See [PROJECT.md](./PROJECT.md) for the detailed implementation notes.

## When To Edit This Repo

Edit this repository when changing Android-native behavior, including:

- WebView configuration
- Android permissions
- notification behavior
- foreground service behavior
- boot receiver behavior
- JavaScript native bridge APIs
- app icon, package name, or app name
- APK build workflow

## When Not To Edit This Repo

Do not edit this repository for ordinary web product changes, including:

- chat page layout
- website routes
- Flask APIs
- gateway behavior
- memory, relay, or tool logic
- CSS/HTML served by the VPS frontend

Those changes belong in the frontend/gateway service, usually on the VPS project that serves `https://love-style.xyz`.

## APK Build

The workflow lives at `.github/workflows/build-apk.yml`.

It currently builds a debug APK on pushes to configured branches and can also be started manually with `workflow_dispatch`. The APK is uploaded as a GitHub Actions artifact named like `Elpis-debug-<run_number>`.

## Quick Orientation For Agents

If you are an AI coding agent: read [AGENTS.md](./AGENTS.md) before deciding whether this repository has an Android app build. The absence of `android/` is expected.