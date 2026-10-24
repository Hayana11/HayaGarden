#!/usr/bin/env bash
# P2H-3A: Reno16 Core-Telecom probe (TelecomSpikeService + TelecomSpikeActivity).
# Standalone: it does not touch the call loop. Runs after apply-health-r1.sh,
# which enables Kotlin. The generated android/ tree is disposable.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
SOURCES=(TelecomSpikeService.kt TelecomSpikeActivity.kt)

for required in "${SOURCES[@]}"; do
  [[ -f "$NATIVE/$required" ]] || { echo "missing $NATIVE/$required" >&2; exit 1; }
done
for required in "$MANIFEST" "$GRADLE"; do
  [[ -f "$required" ]] || { echo "missing $required" >&2; exit 1; }
done
grep -Eq "kotlin-android|org\.jetbrains\.kotlin\.android" "$GRADLE" \
  || { echo "Kotlin is not enabled; run apply-health-r1.sh first" >&2; exit 1; }

mkdir -p "$DEST_DIR"
for source in "${SOURCES[@]}"; do
  cp "$NATIVE/$source" "$DEST_DIR/$source"
done

ensure_dependency() {
  local coordinate="$1"
  local group_artifact="${coordinate%:*}"
  local count
  count="$(grep -Fc "\"$group_artifact:" "$GRADLE" || true)"
  if [[ "$count" -gt 0 ]]; then
    grep -Fq "\"$coordinate\"" "$GRADLE" || { echo "unexpected version of $group_artifact already present" >&2; exit 1; }
    [[ "$count" -eq 1 ]] || { echo "$group_artifact is duplicated" >&2; exit 1; }
    return
  fi
  local tmp
  tmp="$(mktemp)"
  awk -v declaration="implementation \"$coordinate\"" '
    !inserted && $0 ~ /^[[:space:]]*dependencies[[:space:]]*\{/ {
      print
      print "    " declaration
      inserted = 1
      next
    }
    { print }
    END { if (!inserted) exit 1 }
  ' "$GRADLE" > "$tmp" || { rm -f "$tmp"; echo "missing app dependencies block" >&2; exit 1; }
  mv "$tmp" "$GRADLE"
}
ensure_dependency "androidx.core:core-telecom:1.0.1"
ensure_dependency "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"

ensure_permission() {
  if ! grep -Fq "android.permission.$1\"" "$MANIFEST"; then
    sed -i "/<application/i\    <uses-permission android:name=\"android.permission.$1\" />" "$MANIFEST"
  fi
}
# Self-managed VoIP: MANAGE_OWN_CALLS satisfies the phoneCall FGS type
# requirement without becoming the default dialer.
ensure_permission MANAGE_OWN_CALLS
ensure_permission FOREGROUND_SERVICE
ensure_permission FOREGROUND_SERVICE_PHONE_CALL
ensure_permission FOREGROUND_SERVICE_MICROPHONE
ensure_permission USE_FULL_SCREEN_INTENT

if ! grep -Fq 'android:name=".TelecomSpikeService"' "$MANIFEST"; then
  sed -i 's|</application>|        <service\
            android:name=".TelecomSpikeService"\
            android:exported="false"\
            android:foregroundServiceType="phoneCall\|microphone" />\
        <activity\
            android:name=".TelecomSpikeActivity"\
            android:exported="true"\
            android:label="Telecom 探针" />\
    </application>|' "$MANIFEST"
fi

echo "P2H-3A Core-Telecom probe applied"
