#!/usr/bin/env bash
# Phase 1B only: install minimal back-button MainActivity into a freshly
# generated Capacitor Android project. Does NOT restore Phase-0-forbidden
# services, bridges, patches, or permissions.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/MainActivity.java"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
DEST="$DEST_DIR/MainActivity.java"

if [[ ! -f "$SRC" ]]; then
  echo "missing $SRC" >&2
  exit 1
fi
if [[ ! -d "$ROOT/android/app/src/main" ]]; then
  echo "android project missing; run npx cap add android first" >&2
  exit 1
fi

mkdir -p "$DEST_DIR"
cp "$SRC" "$DEST"
echo "installed Phase 1B MainActivity → $DEST"
