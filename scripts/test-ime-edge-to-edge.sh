#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_ROOT="${1:-$ROOT/android}"
MAIN="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary/MainActivity.java"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"

for required in "$MAIN" "$MANIFEST"; do
  [[ -f "$required" ]] || { echo "missing $required" >&2; exit 1; }
done

grep -q 'android:windowSoftInputMode="adjustResize"' "$MANIFEST"
grep -q 'SOFT_INPUT_ADJUST_RESIZE' "$MAIN"
if grep -Eq 'SOFT_INPUT_ADJUST_(NOTHING|PAN)|android:windowSoftInputMode="adjust(Pan|Nothing)"' "$MAIN" "$MANIFEST"; then
  echo "IME must not use adjustNothing or adjustPan" >&2
  exit 1
fi

grep -q 'ViewCompat.setOnApplyWindowInsetsListener' "$MAIN"
grep -q 'WindowInsetsCompat.Type.ime()' "$MAIN"
grep -q 'insets.isVisible(WindowInsetsCompat.Type.ime())' "$MAIN"
grep -q 'return insets' "$MAIN"
if grep -q 'WindowInsetsCompat.CONSUMED' "$MAIN"; then
  echo "IME insets must not be consumed" >&2
  exit 1
fi

grep -q 'baseBottomPadding' "$MAIN"
grep -q 'targetBottomPadding' "$MAIN"
grep -q 'ViewCompat.requestApplyInsets' "$MAIN"
if grep -Eq 'Type\.systemBars\(\).*Type\.ime\(\)|Type\.ime\(\).*Type\.systemBars\(\)' "$MAIN"; then
  echo "IME and navigation/system-bar insets must not be merged here" >&2
  exit 1
fi

grep -q 'setStatusBarColor(Color.TRANSPARENT)' "$MAIN"
grep -q 'SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN' "$MAIN"
if grep -Eq 'setNavigationBarColor|SYSTEM_UI_FLAG_(FULLSCREEN|HIDE_NAVIGATION|IMMERSIVE)' "$MAIN"; then
  echo "edge-to-edge navigation behavior was changed unexpectedly" >&2
  exit 1
fi

echo "test:ime-edge-to-edge — all checks passed"
