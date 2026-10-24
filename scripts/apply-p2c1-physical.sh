#!/usr/bin/env bash
# Phase 2C.1: compose P2B and install the foreground physical cache.
# HMS Activity Identification was removed 2026-10-24 (owner decision: the Reno16
# does not use HMS, and its location SDK merged in location permissions).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P2B="$ROOT/scripts/apply-p2b-native-notifications.sh"
STORE="$ROOT/native/PhysicalStateStore.java"
BRIDGE="$ROOT/native/PhysicalBridge.java"
MAIN="$ROOT/native/MainActivity.java"
INSETS="$ROOT/native/InsetsBridge.java"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/android/app/build.gradle"
ROOT_GRADLE="$ROOT/android/build.gradle"

for required in "$P2B" "$STORE" "$BRIDGE" "$MAIN" "$INSETS"; do
  if [[ ! -f "$required" ]]; then
    echo "missing $required" >&2
    exit 1
  fi
done

bash "$P2B"

mkdir -p "$DEST_DIR"
cp "$STORE" "$DEST_DIR/PhysicalStateStore.java"
cp "$BRIDGE" "$DEST_DIR/PhysicalBridge.java"
cp "$MAIN" "$DEST_DIR/MainActivity.java"
cp "$INSETS" "$DEST_DIR/InsetsBridge.java"

echo "Phase 2C.1 physical patches applied"
