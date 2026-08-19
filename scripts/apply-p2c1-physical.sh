#!/usr/bin/env bash
# Phase 2C.1: compose the verified P2B stack and install only PhysicalBridge.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P2B="$ROOT/scripts/apply-p2b-native-notifications.sh"
SOURCE="$ROOT/native/PhysicalBridge.java"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"

for required in "$P2B" "$SOURCE"; do
  if [[ ! -f "$required" ]]; then
    echo "missing $required" >&2
    exit 1
  fi
done

bash "$P2B"

mkdir -p "$DEST_DIR"
cp "$SOURCE" "$DEST_DIR/PhysicalBridge.java"

echo "Phase 2C.1 PhysicalBridge installed"
