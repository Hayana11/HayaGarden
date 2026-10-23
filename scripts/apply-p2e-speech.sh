#!/usr/bin/env bash
# Phase 2E: on-device speech-to-text bridge for the call page (owner-approved 2026-10-23).
# Adds RECORD_AUDIO (requested only from a tap on the mic) and package visibility of
# speech recognition services (Android 11+ hides them otherwise).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"

for required in "$NATIVE/SpeechBridge.java" "$MANIFEST"; do
  if [[ ! -f "$required" ]]; then
    echo "missing $required" >&2
    exit 1
  fi
done

mkdir -p "$DEST_DIR"
cp "$NATIVE/SpeechBridge.java" "$DEST_DIR/SpeechBridge.java"

if ! grep -Fq 'android.permission.RECORD_AUDIO"' "$MANIFEST"; then
  sed -i '/<application/i\    <uses-permission android:name="android.permission.RECORD_AUDIO" />' "$MANIFEST"
fi
if ! grep -Fq 'android.speech.RecognitionService' "$MANIFEST"; then
  sed -i '/<application/i\    <queries><intent><action android:name="android.speech.RecognitionService" /></intent></queries>' "$MANIFEST"
fi

echo "Phase 2E speech bridge applied"
