#!/usr/bin/env bash
# Phase 2C.1: compose P2B, then install the foreground-live physical cache.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P2B="$ROOT/scripts/apply-p2b-native-notifications.sh"
STORE="$ROOT/native/PhysicalStateStore.java"
BRIDGE="$ROOT/native/PhysicalBridge.java"
INSETS="$ROOT/native/InsetsBridge.java"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"

for required in "$P2B" "$STORE" "$BRIDGE" "$INSETS"; do
  if [[ ! -f "$required" ]]; then
    echo "missing $required" >&2
    exit 1
  fi
done

bash "$P2B"

mkdir -p "$DEST_DIR"
cp "$STORE" "$DEST_DIR/PhysicalStateStore.java"
cp "$BRIDGE" "$DEST_DIR/PhysicalBridge.java"
cp "$INSETS" "$DEST_DIR/InsetsBridge.java"

echo "Phase 2C.1 foreground physical cache installed"
