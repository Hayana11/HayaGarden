#!/usr/bin/env bash
# Phase 2A source + generated-manifest contract locks.
# Run from repo root. Optionally pass path to a generated android/ tree
# (default: ./android). Source checks always run; generated checks skip
# gracefully if android/ is absent.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NB="$ROOT/native/NativeBridge.java"
MA="$ROOT/native/MainActivity.java"
ANDROID_ROOT="${1:-$ROOT/android}"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

# ── A. NativeBridge package ──────────────────────────────────────
if grep -q '^package xyz\.lovestyle\.home\.canary;' "$NB"; then
  pass "NativeBridge package is xyz.lovestyle.home.canary"
else
  fail "NativeBridge package must be xyz.lovestyle.home.canary"
fi
if grep -q 'package xyz\.lovestyle\.home;' "$NB"; then
  fail "NativeBridge must not use production package xyz.lovestyle.home"
fi

# ── B. Exactly six @JavascriptInterface public methods ───────────
EXPECTED_METHODS=(
  getBattery
  getScreenTime
  hasUsageAccess
  openUsageAccessSettings
  isIgnoringBatteryOptimizations
  requestIgnoreBatteryOptimizations
)

# Collect method names that follow @JavascriptInterface (possibly with @SuppressWarnings).
mapfile -t FOUND_METHODS < <(
  awk '
    /@JavascriptInterface/ { pending=1; next }
    pending && /@SuppressWarnings/ { next }
    pending && /public[[:space:]]+(String|boolean|void|int|long|double|float)[[:space:]]+([A-Za-z0-9_]+)[[:space:]]*\(/ {
      print $0
      pending=0
      next
    }
    pending && /public/ { pending=0 }
  ' "$NB" | sed -E 's/.*public[[:space:]]+[A-Za-z0-9_<>]+[[:space:]]+([A-Za-z0-9_]+)[[:space:]]*\(.*/\1/'
)

if [[ ${#FOUND_METHODS[@]} -eq 6 ]]; then
  pass "exactly 6 @JavascriptInterface methods"
else
  fail "expected 6 @JavascriptInterface methods, found ${#FOUND_METHODS[@]}: ${FOUND_METHODS[*]-}"
fi

for m in "${EXPECTED_METHODS[@]}"; do
  if printf '%s\n' "${FOUND_METHODS[@]-}" | grep -qx "$m"; then
    pass "exposes $m"
  else
    fail "missing @JavascriptInterface method: $m"
  fi
done

for m in "${FOUND_METHODS[@]-}"; do
  allowed=0
  for e in "${EXPECTED_METHODS[@]}"; do
    [[ "$m" == "$e" ]] && allowed=1 && break
  done
  if [[ $allowed -eq 0 ]]; then
    fail "unexpected @JavascriptInterface method: $m"
  fi
done

# Forbidden legacy JS surface
for bad in setPocketConfig getPocketStatus openPocketBrowser takeScreenshot \
           requestScreenCapturePermission isScreenCaptureReady stopScreenCapture \
           setScreenCaptureAuto isScreenCaptureAuto; do
  if grep -E "@JavascriptInterface|public .*$bad\(" "$NB" | grep -q "$bad"; then
    fail "forbidden method present in NativeBridge: $bad"
  fi
done

# ── C. Source must not contain forbidden capability tokens ───────
for token in Pocket ScreenCapture ForegroundService NotificationWorker AppTracker WorkManager WebSocket; do
  if grep -q "$token" "$NB" "$MA"; then
    fail "forbidden token '$token' in MainActivity/NativeBridge sources"
  else
    pass "no $token in MainActivity/NativeBridge"
  fi
done

# ── D. MainActivity injects ElpisNative ──────────────────────────
if grep -q 'addJavascriptInterface' "$MA" && grep -q '"ElpisNative"' "$MA"; then
  pass "MainActivity injects ElpisNative"
else
  fail "MainActivity must call addJavascriptInterface(..., \"ElpisNative\")"
fi

# ── E. P1B back semantics retained ───────────────────────────────
for needle in OnBackPressedCallback 'canGoBack()' 'goBack()'; do
  if grep -qF "$needle" "$MA"; then
    pass "MainActivity retains $needle"
  else
    fail "MainActivity missing P1B back piece: $needle"
  fi
done

# ── F. MainActivity forbidden lifecycle / legacy calls ───────────
for bad in setTextZoom startForegroundService requestPermissions AppTracker \
           'reload(' 'loadUrl(' clearCache clearHistory; do
  if grep -qF "$bad" "$MA"; then
    fail "MainActivity must not contain: $bad"
  else
    pass "MainActivity free of $bad"
  fi
done

# ── G/H. Generated manifest (if android/ present) ────────────────
if [[ -f "$MANIFEST" ]]; then
  for need in INTERNET PACKAGE_USAGE_STATS REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; do
    if grep -q "android.permission.${need}" "$MANIFEST"; then
      pass "manifest has $need"
    else
      fail "manifest missing $need"
    fi
  done

  for forbid in POST_NOTIFICATIONS FOREGROUND_SERVICE FOREGROUND_SERVICE_DATA_SYNC \
                FOREGROUND_SERVICE_MEDIA_PROJECTION ACCESS_FINE_LOCATION \
                ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION \
                RECEIVE_BOOT_COMPLETED CAMERA RECORD_AUDIO SYSTEM_ALERT_WINDOW; do
    if grep -q "android.permission.${forbid}" "$MANIFEST"; then
      fail "manifest must not contain $forbid"
    else
      pass "manifest free of $forbid"
    fi
  done

  for component in ForegroundService ScreenCaptureService BootReceiver PocketBrowserActivity \
                   ScreenCapturePermissionActivity NotificationWorker AppTracker; do
    if grep -q "$component" "$MANIFEST"; then
      fail "manifest must not declare $component"
    else
      pass "manifest free of $component"
    fi
  done

  # Custom service/receiver beyond Capacitor/AndroidX defaults: none with our package names.
  if grep -E '<(service|receiver) ' "$MANIFEST" | grep -qiE 'Foreground|ScreenCapture|Boot|Pocket|NotificationWorker|AppTracker'; then
    fail "manifest has forbidden custom service/receiver"
  else
    pass "no forbidden custom service/receiver in manifest"
  fi

  if [[ -f "$GEN_JAVA/NativeBridge.java" ]]; then
    pass "generated NativeBridge.java present"
  else
    fail "generated NativeBridge.java missing under canary package"
  fi
  if [[ -f "$GEN_JAVA/MainActivity.java" ]]; then
    pass "generated MainActivity.java present"
  else
    fail "generated MainActivity.java missing under canary package"
  fi

  # No legacy class files dropped into the canary package tree.
  for legacy in ForegroundService.java ScreenCaptureService.java \
                ScreenCapturePermissionActivity.java NotificationWorker.java \
                AppTracker.java BootReceiver.java PocketManager.java \
                PocketBrowserActivity.java PocketClient.java PocketWebViewPolicy.java; do
    if [[ -f "$GEN_JAVA/$legacy" ]] || find "$ANDROID_ROOT/app/src/main/java" -name "$legacy" 2>/dev/null | grep -q .; then
      fail "legacy class present in android tree: $legacy"
    else
      pass "no legacy $legacy in android tree"
    fi
  done
else
  echo "SKIP generated-manifest checks (no $MANIFEST); source contracts still enforced"
fi

if [[ $FAIL -ne 0 ]]; then
  echo "P2A contract test FAILED" >&2
  exit 1
fi
echo "P2A contract test PASSED"
