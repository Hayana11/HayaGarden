#!/usr/bin/env bash
# Inject the independent Health Connect bridge into the generated Capacitor project.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
ROOT_GRADLE="$ROOT/android/build.gradle"
SOURCE_DIR="$ROOT/native"

for required in HealthBridge.java HealthConnectReader.kt HealthStateStore.java HealthSupport.java HealthSyncWorker.java BuildInfo.java HealthConfig.java; do
  if [[ ! -f "$SOURCE_DIR/$required" ]]; then
    echo "missing $SOURCE_DIR/$required" >&2
    exit 1
  fi
done

mkdir -p "$DEST_DIR"
for source in BuildInfo.java HealthConfig.java HealthBridge.java HealthStateStore.java HealthSupport.java HealthSyncWorker.java HealthConnectReader.kt; do
  cp "$SOURCE_DIR/$source" "$DEST_DIR/$source"
done

if ! grep -Fq "androidx.health.connect:connect-client" "$GRADLE"; then
  sed -i '/dependencies[[:space:]]*{/a\    implementation "androidx.health.connect:connect-client:1.1.0"' "$GRADLE"
fi
if ! grep -Fq "androidx.work:work-runtime-ktx" "$GRADLE"; then
  sed -i '/dependencies[[:space:]]*{/a\    implementation "androidx.work:work-runtime-ktx:2.9.0"' "$GRADLE"
fi
if grep -Fq "plugins {" "$ROOT_GRADLE"; then
  if ! grep -Fq "org.jetbrains.kotlin.android" "$ROOT_GRADLE"; then
    sed -i '/^plugins[[:space:]]*{/a\    id "org.jetbrains.kotlin.android" version "2.2.20" apply false' "$ROOT_GRADLE"
  fi
elif ! grep -Fq "kotlin-gradle-plugin" "$ROOT_GRADLE"; then
  sed -i '/dependencies[[:space:]]*{/a\        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20"' "$ROOT_GRADLE"
fi
if grep -Fq "plugins {" "$GRADLE"; then
  if ! grep -Fq "org.jetbrains.kotlin.android" "$GRADLE"; then
    sed -i '/^plugins[[:space:]]*{/a\    id "org.jetbrains.kotlin.android"' "$GRADLE"
  fi
elif ! grep -Fq "kotlin-android" "$GRADLE"; then
  sed -i "/com.android.application/a apply plugin: 'kotlin-android'" "$GRADLE"
fi

ensure_manifest_line() {
  local needle="$1"
  local line="$2"
  if ! grep -Fq "$needle" "$MANIFEST"; then
    sed -i "/<application/i\    $line" "$MANIFEST"
  fi
}
ensure_manifest_line "android.permission.health.READ_HEART_RATE" '<uses-permission android:name="android.permission.health.READ_HEART_RATE" />'
ensure_manifest_line "android.permission.health.READ_STEPS" '<uses-permission android:name="android.permission.health.READ_STEPS" />'
ensure_manifest_line "android.permission.health.READ_SLEEP" '<uses-permission android:name="android.permission.health.READ_SLEEP" />'
ensure_manifest_line "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" '<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />'
if ! grep -Fq 'com.google.android.apps.healthdata' "$MANIFEST"; then
  sed -i '/<application/i\    <queries><package android:name="com.google.android.apps.healthdata" /></queries>' "$MANIFEST"
fi

SOURCE_SHA="${GITHUB_SHA:-unknown}"
BRANCH="${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-unknown}}"
TOKEN="${HEALTH_INGEST_TOKEN:-}"
printf '%s\n' \
  'package xyz.lovestyle.home.canary;' \
  '' \
  '/** Generated build provenance; no secret is stored here. */' \
  'public final class BuildInfo {' \
  "    public static final String SOURCE_SHA = \"$SOURCE_SHA\";" \
  "    public static final String BRANCH = \"$BRANCH\";" \
  '    private BuildInfo() {}' \
  '}' > "$DEST_DIR/BuildInfo.java"
printf '%s\n' \
  'package xyz.lovestyle.home.canary;' \
  '' \
  '/** Generated only in the CI build workspace. */' \
  'public final class HealthConfig {' \
  "    public static final String INGEST_TOKEN = \"$TOKEN\";" \
  '    public static final String INGEST_URL = "https://love-style.xyz/api/health/mobile/ingest";' \
  '    private HealthConfig() {}' \
  '}' > "$DEST_DIR/HealthConfig.java"

echo "Health Bridge R1 injected (token configured: $([[ -n "$TOKEN" ]] && echo yes || echo no))"
