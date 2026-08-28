#!/usr/bin/env bash
# HMS Activity R1 generated-tree and minimal-permission contract.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_ROOT="${1:-$ROOT/android}"
STORE="$ROOT/native/HmsActivityStore.java"
RECEIVER="$ROOT/native/HmsActivityReceiver.java"
BRIDGE="$ROOT/native/HmsActivityBridge.java"
MAIN="$ROOT/native/MainActivity.java"
PATCH="$ROOT/scripts/apply-p2c1-physical.sh"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
GRADLE="$ANDROID_ROOT/app/build.gradle"
GEN="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
require() { [[ -f "$1" ]] && pass "present $1" || fail "missing $1"; }

for file in "$STORE" "$RECEIVER" "$BRIDGE" "$MAIN" "$PATCH"; do
  require "$file"
done

for token in ActivityIdentificationService createActivityIdentificationUpdates PendingIntent; do
  grep -q "$token" "$STORE" || fail "HMS store missing $token"
done
grep -q 'getDataFromIntent' "$STORE" || fail "HMS response parsing missing"
grep -q 'getIdentificationActivity' "$STORE" || fail "numeric HMS activity field missing"
grep -q 'ActivityIdentificationData.STILL' "$STORE" || fail "HMS numeric activity mapping missing"
FOOT_MAPPING="$(sed -n '/case ActivityIdentificationData.FOOT:/,/default:/p' "$STORE")"
printf '%s\n' "$FOOT_MAPPING" | grep -q 'return "unknown"' || fail "ambiguous FOOT/ON_FOOT must map to unknown"
if printf '%s\n' "$FOOT_MAPPING" | grep -q 'return "walking"'; then
  fail "ambiguous FOOT/ON_FOOT must not map to walking"
fi
pass "ambiguous FOOT/ON_FOOT maps to unknown"
grep -q 'PhysicalStateStore' "$MAIN" || fail "existing physical motion store was removed"
grep -q '"ElpisPhysical"' "$MAIN" || fail "existing physical motion bridge was removed"
grep -q 'activitySampledAt' "$BRIDGE" || fail "activitySampledAt missing"
grep -q 'System.currentTimeMillis' "$STORE" || fail "native callback timestamp missing"
grep -q 'ElpisActivity' "$MAIN" || fail "HMS JS bridge not injected"
grep -q 'hmsActivityStore.startIfPermitted' "$MAIN" || fail "HMS start hook missing"
if grep -q 'hmsActivityStore.stop' "$MAIN"; then
  fail "HMS updates must not stop in onPause"
fi

for permission in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  if grep -q "$permission" "$STORE" "$RECEIVER" "$BRIDGE" "$MAIN" "$PATCH"; then
    fail "location permission must not be added for HMS Activity R1: $permission"
  fi
done
grep -q 'android.permission.ACTIVITY_RECOGNITION' "$PATCH" || fail "Android 10+ permission missing"
grep -q 'com.huawei.hms.permission.ACTIVITY_RECOGNITION' "$PATCH" || fail "legacy permission missing"
grep -q 'HMS_LOCATION_VERSION="6.4.0.300"' "$PATCH" || fail "SDK version missing"
grep -q 'AGCONNECT_SERVICES_JSON_B64' "$ROOT/.github/workflows/build-apk.yml" || fail "secure AGConnect injection missing"
if [[ -f "$ROOT/agconnect-services.json" || -f "$ROOT/android/app/agconnect-services.json" ]]; then
  fail "AGConnect config must not be committed"
fi

if [[ -f "$MANIFEST" ]]; then
  grep -q 'android.permission.ACTIVITY_RECOGNITION' "$MANIFEST" || fail "generated Android permission missing"
  grep -q 'com.huawei.hms.permission.ACTIVITY_RECOGNITION' "$MANIFEST" || fail "generated legacy permission missing"
  for permission in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
    if grep -q "android.permission.$permission" "$MANIFEST"; then
      fail "generated manifest contains forbidden location permission: $permission"
    fi
  done
  grep -q 'HmsActivityReceiver' "$MANIFEST" || fail "generated receiver missing"
  pass "generated manifest has only Activity Recognition permissions"
fi

if [[ -f "$GRADLE" ]]; then
  grep -q 'com.huawei.hms:location:6.4.0.300' "$GRADLE" || fail "generated HMS dependency missing"
  pass "generated Gradle has Location SDK"
fi

if [[ -d "$GEN" ]]; then
  for file in PhysicalStateStore.java PhysicalBridge.java MainActivity.java HmsActivityStore.java HmsActivityReceiver.java HmsActivityBridge.java; do
    require "$GEN/$file"
  done
fi

pass "HMS Activity R1 contract passed"
