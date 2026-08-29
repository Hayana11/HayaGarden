#!/usr/bin/env bash
# Phase 2C.1: compose P2B, install the foreground physical cache, and add
# independent HMS Activity Identification. No location permissions are added.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P2B="$ROOT/scripts/apply-p2b-native-notifications.sh"
STORE="$ROOT/native/PhysicalStateStore.java"
BRIDGE="$ROOT/native/PhysicalBridge.java"
MAIN="$ROOT/native/MainActivity.java"
HMS_STORE="$ROOT/native/HmsActivityStore.java"
HMS_RECEIVER="$ROOT/native/HmsActivityReceiver.java"
HMS_BRIDGE="$ROOT/native/HmsActivityBridge.java"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
ROOT_GRADLE="$ROOT/android/build.gradle"
SETTINGS="$ROOT/android/settings.gradle"
HMS_LOCATION_VERSION="6.4.0.300"

for required in "$P2B" "$STORE" "$BRIDGE" "$MAIN" "$HMS_STORE" "$HMS_RECEIVER" "$HMS_BRIDGE"; do
  if [[ ! -f "$required" ]]; then
    echo "missing $required" >&2
    exit 1
  fi
done

bash "$P2B"

mkdir -p "$DEST_DIR"
cp "$STORE" "$DEST_DIR/PhysicalStateStore.java"
cp "$BRIDGE" "$DEST_DIR/PhysicalBridge.java"
cp "$MAIN" "$DEST_DIR/MainActivity.java"
cp "$HMS_STORE" "$DEST_DIR/HmsActivityStore.java"
cp "$HMS_RECEIVER" "$DEST_DIR/HmsActivityReceiver.java"
cp "$HMS_BRIDGE" "$DEST_DIR/HmsActivityBridge.java"

if ! grep -Fq "com.huawei.hms:location:" "$GRADLE"; then
  sed -i "/dependencies[[:space:]]*{/a\    implementation \"com.huawei.hms:location:$HMS_LOCATION_VERSION\"" "$GRADLE"
fi

for gradle_file in "$ROOT_GRADLE" "$SETTINGS"; do
  if [[ -f "$gradle_file" ]] && ! grep -Fq "developer.huawei.com/repo" "$gradle_file"; then
    sed -i '/repositories[[:space:]]*{/a\        maven { url "https://developer.huawei.com/repo/" }' "$gradle_file"
  fi
done

if [[ -f "$ROOT/android/app/agconnect-services.json" ]]; then
  if ! grep -Fq "com.huawei.agconnect:agcp" "$ROOT_GRADLE"; then
    if grep -q "buildscript" "$ROOT_GRADLE"; then
      sed -i '/dependencies[[:space:]]*{/a\        classpath "com.huawei.agconnect:agcp:1.9.6.300"' "$ROOT_GRADLE"
    else
      echo "AGConnect config present but generated root Gradle has no buildscript block; plugin wiring skipped" >&2
    fi
  fi
  if ! grep -Fq "apply plugin: 'com.huawei.agconnect'" "$GRADLE"; then
    sed -i "/com.android.application/a apply plugin: 'com.huawei.agconnect'" "$GRADLE"
  fi
fi

ensure_manifest_line() {
  local needle="$1"
  local line="$2"
  if ! grep -Fq "$needle" "$MANIFEST"; then
    sed -i "/android.permission.INTERNET/a\
    $line" "$MANIFEST"
  fi
}

ensure_manifest_line "android.permission.ACTIVITY_RECOGNITION"   '<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />'
ensure_manifest_line "com.huawei.hms.permission.ACTIVITY_RECOGNITION"   '<uses-permission android:name="com.huawei.hms.permission.ACTIVITY_RECOGNITION" />'

if ! grep -Fq "HmsActivityReceiver" "$MANIFEST"; then
  sed -i 's|</application>|        <receiver android:name=".HmsActivityReceiver" android:exported="false" />\
    </application>|' "$MANIFEST"
fi

echo "Phase 2C.1 physical + HMS Activity Identification patches applied"
