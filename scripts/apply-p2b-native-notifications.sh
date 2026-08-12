#!/usr/bin/env bash
# Phase 2B: compose P2A, then layer notification-only native support.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P2A="$ROOT/scripts/apply-p2a-nativebridge-lite.sh"
NATIVE="$ROOT/native"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"

for required in "$P2A" "$NATIVE/NotificationSupport.java" \
                "$NATIVE/NotificationPollWorker.java" "$NATIVE/NotificationBridge.java"; do
  if [[ ! -f "$required" ]]; then
    echo "missing $required" >&2
    exit 1
  fi
done
if [[ ! -f "$MANIFEST" || ! -f "$GRADLE" ]]; then
  echo "android project missing; run npx cap add android first" >&2
  exit 1
fi

bash "$P2A"

mkdir -p "$DEST_DIR"
cp "$NATIVE/NotificationSupport.java" "$DEST_DIR/NotificationSupport.java"
cp "$NATIVE/NotificationPollWorker.java" "$DEST_DIR/NotificationPollWorker.java"
cp "$NATIVE/NotificationBridge.java" "$DEST_DIR/NotificationBridge.java"

if ! grep -q 'android.permission.POST_NOTIFICATIONS' "$MANIFEST"; then
  sed -i '/android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS/a\
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />' "$MANIFEST"
fi

if ! grep -Fq 'androidx.work:work-runtime:2.9.0' "$GRADLE"; then
  cat >> "$GRADLE" <<'GRADLE'

dependencies {
    implementation "androidx.work:work-runtime:2.9.0"
}
GRADLE
fi

echo "Phase 2B native notifications applied"
