#!/usr/bin/env bash
# Inject the independent Health Connect bridge into the generated Capacitor project.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
ROOT_GRADLE="$ROOT/android/build.gradle"
VARIABLES_GRADLE="$ROOT/android/variables.gradle"
SOURCE_DIR="$ROOT/native"

for required in HealthBridge.java HealthConnectReader.kt HealthStateStore.java HealthCredentialStore.java HealthSupport.java HealthSyncWorker.java BuildInfo.java HealthConfig.java HealthPermissionsRationaleActivity.java; do
  if [[ ! -f "$SOURCE_DIR/$required" ]]; then
    echo "missing $SOURCE_DIR/$required" >&2
    exit 1
  fi
done

# Health Connect SDK 1.1.0 declares minSdk 26. API 24-25 cannot install
# this Health R1 APK; API 26-27 may install but Health Connect can be
# unavailable and must surface UNAVAILABLE; API 28+ is eligible where supported.
# Keep the generated root ext value as the sole minSdk authority and do not
# bypass manifest merger validation.
if [[ ! -f "$VARIABLES_GRADLE" ]]; then
  echo "missing generated minSdk authority: $VARIABLES_GRADLE" >&2
  exit 1
fi
MIN_SDK_ASSIGNMENTS="$(grep -Ec '^[[:space:]]*minSdkVersion[[:space:]]*=' "$VARIABLES_GRADLE" || true)"
if [[ "$MIN_SDK_ASSIGNMENTS" != "1" ]]; then
  echo "expected exactly one minSdkVersion assignment in $VARIABLES_GRADLE; found $MIN_SDK_ASSIGNMENTS" >&2
  exit 1
fi
MIN_SDK_VALUE="$(sed -n -E 's/^[[:space:]]*minSdkVersion[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' "$VARIABLES_GRADLE" | head -n 1)"
if [[ ! "$MIN_SDK_VALUE" =~ ^[0-9]+$ ]]; then
  echo "could not read minSdkVersion from $VARIABLES_GRADLE" >&2
  exit 1
fi
if (( MIN_SDK_VALUE < 26 )); then
  sed -i -E 's/^[[:space:]]*minSdkVersion[[:space:]]*=.*$/    minSdkVersion = 26/' "$VARIABLES_GRADLE"
fi

mkdir -p "$DEST_DIR"
for source in BuildInfo.java HealthConfig.java HealthBridge.java HealthStateStore.java HealthCredentialStore.java HealthSupport.java HealthSyncWorker.java HealthConnectReader.kt HealthPermissionsRationaleActivity.java; do
  cp "$SOURCE_DIR/$source" "$DEST_DIR/$source"
done

ensure_app_dependency() {
  local coordinate="$1"
  local declaration="implementation \"$coordinate\""
  if grep -Fq "$declaration" "$GRADLE"; then
    return
  fi
  local tmp
  tmp="$(mktemp)"
  if ! awk -v declaration="$declaration" '
    !inserted && $0 ~ /^[[:space:]]*dependencies[[:space:]]*\{/ {
      print
      print "    " declaration
      inserted = 1
      next
    }
    { print }
    END {
      if (!inserted) exit 1
    }
  ' "$GRADLE" > "$tmp"; then
    rm -f "$tmp"
    echo "missing canonical app dependencies block in $GRADLE" >&2
    exit 1
  fi
  mv "$tmp" "$GRADLE"
}

# P2B owns work-runtime:2.9.0 in its appended block. Health R1 owns the
# remaining compile-visible dependencies, inserted once into the first app
# dependencies block so repeated runs do not fan out across blocks.
ensure_app_dependency "androidx.health.connect:connect-client:1.1.0"
ensure_app_dependency "androidx.work:work-runtime-ktx:2.9.0"
ensure_app_dependency "com.google.guava:guava:31.1-android"

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
  if grep -Fq "plugins {" "$ROOT_GRADLE"; then
    sed -i "/com.android.application/a apply plugin: 'org.jetbrains.kotlin.android'" "$GRADLE"
  else
    sed -i "/com.android.application/a apply plugin: 'kotlin-android'" "$GRADLE"
  fi
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


ensure_manifest_application_block() {
  local marker="$1"
  local block="$2"
  if grep -Fq "$marker" "$MANIFEST"; then
    return
  fi
  local tmp
  tmp="$(mktemp)"
  if ! awk -v block="$block" '
    !inserted && /<\/application>/ {
      print block
      inserted = 1
    }
    { print }
    END {
      if (!inserted) exit 1
    }
  ' "$MANIFEST" > "$tmp"; then
    rm -f "$tmp"
    echo "missing closing application block in $MANIFEST" >&2
    exit 1
  fi
  mv "$tmp" "$MANIFEST"
}

rationale_activity_block="$(cat <<'EOF'
    <activity
        android:name=".HealthPermissionsRationaleActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
        </intent-filter>
    </activity>
EOF
)"
ensure_manifest_application_block   'androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE'   "$rationale_activity_block"

usage_activity_alias_block="$(cat <<'EOF'
    <activity-alias
        android:name=".ViewPermissionUsageActivity"
        android:exported="true"
        android:targetActivity=".HealthPermissionsRationaleActivity"
        android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
        <intent-filter>
            <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
            <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
        </intent-filter>
    </activity-alias>
EOF
)"
ensure_manifest_application_block   'android.intent.action.VIEW_PERMISSION_USAGE'   "$usage_activity_alias_block"

SOURCE_SHA="${GITHUB_SHA:-unknown}"
BRANCH="${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-unknown}}"
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
  '    public static final String INGEST_URL = "https://love-style.xyz/api/health/mobile/ingest";' \
  '    private HealthConfig() {}' \
  '}' > "$DEST_DIR/HealthConfig.java"

echo "Health Bridge R1 injected (device-scoped credential provisioning required)"
