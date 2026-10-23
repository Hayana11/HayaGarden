#!/usr/bin/env bash
# Phase 2F: native AudioRecord + Silero VAD voice capture.
# The generated android/ tree is deliberately disposable; native/ and this
# script are the source of truth.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
ASSET_DIR="$ROOT/android/app/src/main/assets"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
MODEL="$NATIVE/assets/silero_vad.onnx"
EXPECTED_MODEL_SHA="2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f"

for required in VoiceBridge.java VoiceCapture.java SileroVad.java VoiceSegmenter.java WavEncoder.java MainActivity.java; do
  [[ -f "$NATIVE/$required" ]] || { echo "missing $NATIVE/$required" >&2; exit 1; }
done
for required in "$MANIFEST" "$GRADLE" "$MODEL"; do
  [[ -f "$required" ]] || { echo "missing $required" >&2; exit 1; }
done

actual_sha="$(sha256sum "$MODEL" | awk '{print $1}')"
[[ "$actual_sha" == "$EXPECTED_MODEL_SHA" ]] || {
  echo "Silero model SHA256 mismatch: $actual_sha" >&2
  exit 1
}

mkdir -p "$DEST_DIR" "$ASSET_DIR"
for source in VoiceBridge.java VoiceCapture.java SileroVad.java VoiceSegmenter.java WavEncoder.java MainActivity.java; do
  cp "$NATIVE/$source" "$DEST_DIR/$source"
done
cp "$MODEL" "$ASSET_DIR/silero_vad.onnx"

ort_version='com.microsoft.onnxruntime:onnxruntime-android:1.20.0'
ort_count="$(grep -Ec 'com\.microsoft\.onnxruntime:onnxruntime-android:' "$GRADLE" || true)"
if [[ "$ort_count" -gt 0 ]]; then
  grep -Fq "$ort_version" "$GRADLE" || {
    echo "an unexpected ONNX Runtime version is already present" >&2
    exit 1
  }
  [[ "$ort_count" -eq 1 ]] || { echo "ONNX Runtime dependency is duplicated" >&2; exit 1; }
else
  tmp="$(mktemp)"
  awk -v declaration="implementation \"$ort_version\"" '
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

if ! grep -Fq 'android.permission.RECORD_AUDIO"' "$MANIFEST"; then
  sed -i '/<application/i\    <uses-permission android:name="android.permission.RECORD_AUDIO" />' "$MANIFEST"
fi

abi_count="$(grep -Ec 'abiFilters' "$GRADLE" || true)"
if [[ "$abi_count" -eq 0 ]]; then
  tmp="$(mktemp)"
  awk '
    !inserted && /^[[:space:]]*defaultConfig[[:space:]]*\{/ {
      print
      print "        ndk { abiFilters \"arm64-v8a\" }"
      inserted = 1
      next
    }
    { print }
    END { if (!inserted) exit 1 }
  ' "$GRADLE" > "$tmp" || { rm -f "$tmp"; echo "missing defaultConfig block" >&2; exit 1; }
  mv "$tmp" "$GRADLE"
else
  grep -Eq 'abiFilters[^\n]*["'\'' ]arm64-v8a' "$GRADLE" || {
    echo "existing ABI filter does not include arm64-v8a" >&2
    exit 1
  }
  if grep -Eq 'abiFilters[^\n]*(x86|armeabi-v7a|x86_64)' "$GRADLE"; then
    echo "P2F Canary must be arm64-v8a only" >&2
    exit 1
  fi
fi

echo "P2F native voice applied (arm64-v8a only)"
