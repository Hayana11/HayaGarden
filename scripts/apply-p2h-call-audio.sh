#!/usr/bin/env bash
# Phase 2H-1: native call audio (his voice) with earpiece/speaker routing.
# The generated android/ tree is deliberately disposable; native/ and this
# script are the source of truth. Runs after apply-p2f-voice.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
SOURCES=(CallAudioBridge.java CallAudioUrls.java MainActivity.java)

for required in "${SOURCES[@]}"; do
  [[ -f "$NATIVE/$required" ]] || { echo "missing $NATIVE/$required" >&2; exit 1; }
done
for required in "$MANIFEST" "$GRADLE"; do
  [[ -f "$required" ]] || { echo "missing $required" >&2; exit 1; }
done

mkdir -p "$DEST_DIR"
for source in "${SOURCES[@]}"; do
  cp "$NATIVE/$source" "$DEST_DIR/$source"
done

media3_version='androidx.media3:media3-exoplayer:1.8.0'
media3_count="$(grep -Ec 'androidx\.media3:media3-exoplayer:' "$GRADLE" || true)"
if [[ "$media3_count" -gt 0 ]]; then
  grep -Fq "$media3_version" "$GRADLE" || {
    echo "an unexpected Media3 ExoPlayer version is already present" >&2
    exit 1
  }
  [[ "$media3_count" -eq 1 ]] || { echo "Media3 ExoPlayer dependency is duplicated" >&2; exit 1; }
else
  tmp="$(mktemp)"
  awk -v declaration="implementation \"$media3_version\"" '
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
fi

if ! grep -Fq 'android.permission.MODIFY_AUDIO_SETTINGS"' "$MANIFEST"; then
  sed -i '/<application/i\    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />' "$MANIFEST"
fi

echo "P2H-1 native call audio applied"
