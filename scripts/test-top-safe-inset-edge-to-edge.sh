#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary/MainActivity.java"
BRIDGE="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary/InsetsBridge.java"
SOURCE="$ROOT/native/InsetsBridge.java"

for required in "$MAIN" "$BRIDGE" "$SOURCE"; do
  [[ -f "$required" ]] || { echo "missing $required" >&2; exit 1; }
done

grep -q 'new InsetsBridge(getApplicationContext(), webView)' "$MAIN"
grep -q '"ElpisInsets"' "$MAIN"
grep -q 'configureTopStatusBar' "$MAIN"
grep -q 'private static final int SYSTEM_BAR_SURFACE_COLOR = Color.TRANSPARENT;' "$MAIN"
grep -q 'setStatusBarColor(SYSTEM_BAR_SURFACE_COLOR)' "$MAIN"
grep -q 'setNavigationBarColor(SYSTEM_BAR_SURFACE_COLOR)' "$MAIN"
grep -q 'setNavigationBarDividerColor(SYSTEM_BAR_SURFACE_COLOR)' "$MAIN"
grep -q 'SYSTEM_UI_FLAG_LAYOUT_STABLE' "$MAIN"
grep -q 'SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN' "$MAIN"
grep -q 'SYSTEM_UI_FLAG_LIGHT_STATUS_BAR' "$MAIN"
grep -q 'SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR' "$MAIN"
grep -q 'setNavigationBarContrastEnforced(false)' "$MAIN"
grep -q 'Build.VERSION_CODES.O' "$MAIN"
grep -q 'Build.VERSION_CODES.P' "$MAIN"
grep -q 'Build.VERSION_CODES.Q' "$MAIN"

if grep -Eq 'set(Status|Navigation)BarColor\((Color\.parseColor|0x|[0-9]+)' "$MAIN"; then
  echo "system-bar colors must use the shared source" >&2
  exit 1
fi
if grep -Eq 'SYSTEM_UI_FLAG_(HIDE_NAVIGATION|IMMERSIVE)|setDecorFitsSystemWindows|LAYOUT_HIDE_NAVIGATION' "$MAIN"; then
  echo "current edge-to-edge behavior was changed unexpectedly" >&2
  exit 1
fi

[[ "$(grep -c '@JavascriptInterface' "$BRIDGE")" -eq 1 ]]
grep -q 'public String getTopInset()' "$BRIDGE"
grep -q 'ViewCompat.getRootWindowInsets' "$BRIDGE"
grep -q 'WindowInsetsCompat.Type.statusBars' "$BRIDGE"
grep -q 'status_bar_height' "$BRIDGE"
grep -q 'getDisplayMetrics().density' "$BRIDGE"
grep -q 'topInsetCssPx' "$BRIDGE"

if grep -Eq 'addJavascriptInterface|setStatusBar|setNavigationBar|loadUrl|WebView|extends Activity' "$BRIDGE"; then
  echo "bridge owns an out-of-contract side effect" >&2
  exit 1
fi

for preserved in   '"ElpisNative"'   '"ElpisNotifications"'   '"ElpisPhysical"'   'physicalStateStore.start()'   'physicalStateStore.stop()'   'wv.canGoBack()'   'wv.goBack()'   'NotificationSupport.ensurePollingScheduled'; do
  grep -q "$preserved" "$MAIN"
done

cmp -s "$BRIDGE" "$SOURCE"

echo "test:top-safe-inset-edge-to-edge — all checks passed"
