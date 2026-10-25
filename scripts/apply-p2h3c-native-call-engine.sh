#!/usr/bin/env bash
# P2H-3C: native call engine (NativeCallEngine, a Core-Telecom phoneCall service)
# and its standalone acceptance screen. CallScreen keeps the WebView runtime (the
# 3E capability switch decides); the engine reuses VoiceCapture and Media3 from
# P2F/P2H-1. Runs after apply-health-r1.sh (Kotlin) and apply-p2h-call-audio.sh
# (Media3). The generated android/ tree is disposable.
# The Core-Telecom part follows the 3A probe that passed on Reno16 (2026-10-24).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
SOURCES=(NativeCallEngine.kt NativeCallApi.kt NativeCallProtocol.java CallRunGate.java CallStartIds.java CallLanes.java AppRouteProbe.java NativeCallProbeActivity.kt)

for required in "${SOURCES[@]}"; do
  [[ -f "$NATIVE/$required" ]] || { echo "missing $NATIVE/$required" >&2; exit 1; }
done
for required in "$MANIFEST" "$GRADLE"; do
  [[ -f "$required" ]] || { echo "missing $required" >&2; exit 1; }
done
grep -Eq "kotlin-android|org\.jetbrains\.kotlin\.android" "$GRADLE" \
  || { echo "Kotlin is not enabled; run apply-health-r1.sh first" >&2; exit 1; }
grep -Fq '"androidx.media3:media3-exoplayer:' "$GRADLE" \
  || { echo "Media3 is missing; run apply-p2h-call-audio.sh first" >&2; exit 1; }
for reused in VoiceCapture.java VoiceSegmenter.java WavEncoder.java CallAudioUrls.java; do
  [[ -f "$DEST_DIR/$reused" ]] || { echo "missing generated $reused; run apply-p2f-voice.sh and apply-p2h-call-audio.sh first" >&2; exit 1; }
done

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
# Latest official Core-Telecom (Upstream Fidelity: reproduce here before any OEM
# workaround). It needs compileSdk 36.1 (AGP >= 8.9.1; the template's AGP is
# 8.13.0); targetSdk stays as generated, so runtime behaviour does not change.
ensure_dependency "androidx.core:core-telecom:1.1.0-rc01"
ensure_dependency "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"

if ! grep -Eq '^[[:space:]]*compileSdkMinor[[:space:]]*=' "$GRADLE"; then
  grep -Eq '^[[:space:]]*compileSdk[[:space:]]*=' "$GRADLE" \
    || { echo "missing compileSdk line in $GRADLE" >&2; exit 1; }
  sed -i -E '0,/^([[:space:]]*)compileSdk[[:space:]]*=.*$/s//&\n\1compileSdkMinor = 1/' "$GRADLE"
fi

ensure_permission() {
  if ! grep -Fq "android.permission.$1\"" "$MANIFEST"; then
    sed -i "/<application/i\    <uses-permission android:name=\"android.permission.$1\" />" "$MANIFEST"
  fi
}
# Self-managed VoIP: MANAGE_OWN_CALLS satisfies the phoneCall FGS type
# requirement without becoming the default dialer (owner-approved 2026-10-24).
# The microphone FGS type is deliberately not declared.
ensure_permission MANAGE_OWN_CALLS
ensure_permission FOREGROUND_SERVICE
ensure_permission FOREGROUND_SERVICE_PHONE_CALL
ensure_permission USE_FULL_SCREEN_INTENT

# The acceptance screen is exported only so adb can open it (no launcher entry);
# it starts nothing by itself, every call needs a tap on it.
if ! grep -Fq 'android:name=".NativeCallEngine"' "$MANIFEST"; then
  sed -i 's|</application>|        <service\
            android:name=".NativeCallEngine"\
            android:exported="false"\
            android:foregroundServiceType="phoneCall" />\
        <activity\
            android:name=".NativeCallProbeActivity"\
            android:exported="true"\
            android:label="原生通话验收" />\
    </application>|' "$MANIFEST"
fi

echo "P2H-3C native call engine applied"
