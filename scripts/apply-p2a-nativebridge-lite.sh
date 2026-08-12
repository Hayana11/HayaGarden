#!/usr/bin/env bash
# Phase 2A: compose P1B (MainActivity + stable debug signing), then layer
# NativeBridge Lite + minimal PACKAGE_USAGE_STATS / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
# Idempotent. Does NOT restore Pocket / ScreenCapture / services / trackers.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P1="$ROOT/scripts/apply-p1-navigation.sh"
NB_SRC="$ROOT/native/NativeBridge.java"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
NB_DEST="$DEST_DIR/NativeBridge.java"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"

if [[ ! -f "$P1" ]]; then
  echo "missing $P1" >&2
  exit 1
fi
if [[ ! -f "$NB_SRC" ]]; then
  echo "missing $NB_SRC" >&2
  exit 1
fi
if [[ ! -d "$ROOT/android/app/src/main" ]]; then
  echo "android project missing; run npx cap add android first" >&2
  exit 1
fi
if [[ ! -f "$MANIFEST" ]]; then
  echo "missing $MANIFEST" >&2
  exit 1
fi

# 1. Reuse P1B MainActivity install + stable debug signing.
bash "$P1"

# 2. Install NativeBridge Lite next to canary MainActivity.
mkdir -p "$DEST_DIR"
cp "$NB_SRC" "$NB_DEST"
echo "installed Phase 2A NativeBridge → $NB_DEST"

# 3. Ensure xmlns:tools on the manifest root (needed for ProtectedPermissions ignore).
if ! grep -q 'xmlns:tools=' "$MANIFEST"; then
  sed -i 's|xmlns:android="http://schemas.android.com/apk/res/android">|xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools">|' "$MANIFEST"
  echo "added xmlns:tools to AndroidManifest.xml"
else
  echo "xmlns:tools already present in AndroidManifest.xml"
fi

# 4. Idempotent minimal permission inserts after INTERNET (or after existing block).
ensure_permission() {
  local name="$1"
  local line="$2"
  if grep -q "android.permission.${name}" "$MANIFEST"; then
    echo "permission ${name} already present"
    return 0
  fi
  if ! grep -q 'android.permission.INTERNET' "$MANIFEST"; then
    echo "INTERNET permission not found; cannot anchor P2A permissions" >&2
    exit 1
  fi
  # Insert after INTERNET line (or after last already-inserted P2A permission if re-run mid-block).
  local anchor='android.permission.INTERNET'
  if grep -q 'android.permission.PACKAGE_USAGE_STATS' "$MANIFEST"; then
    anchor='android.permission.PACKAGE_USAGE_STATS'
  fi
  if grep -q 'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' "$MANIFEST"; then
    anchor='android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
  fi
  sed -i "/${anchor}/a\\
    ${line}" "$MANIFEST"
  echo "inserted permission ${name}"
}

ensure_permission "PACKAGE_USAGE_STATS" \
  '<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />'
ensure_permission "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" \
  '<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />'

echo "Phase 2A NativeBridge Lite patches applied"
