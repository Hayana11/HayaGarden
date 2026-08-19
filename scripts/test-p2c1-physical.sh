#!/usr/bin/env bash
# P2C.1 source and generated-tree contract locks.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRIDGE="$ROOT/native/PhysicalBridge.java"
MAIN="$ROOT/native/MainActivity.java"
ANDROID_ROOT="${1:-$ROOT/android}"
GEN="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

[[ -f "$BRIDGE" ]] && pass "PhysicalBridge source present" || fail "PhysicalBridge source missing"
[[ "$(grep -c '@JavascriptInterface' "$BRIDGE")" -eq 1 ]] \
  && pass "PhysicalBridge exposes one JS method" \
  || fail "PhysicalBridge must expose exactly one JS method"
grep -q 'public String getPhysicalState()' "$BRIDGE" \
  && pass "getPhysicalState exists" || fail "getPhysicalState missing"
grep -q 'schemaVersion' "$BRIDGE" \
  && pass "schemaVersion is present" || fail "schemaVersion missing"
grep -q 'sampledAt' "$BRIDGE" \
  && pass "sampledAt is present" || fail "sampledAt missing"
grep -q 'SNAPSHOT_TIMEOUT_MS = 1000L' "$BRIDGE" \
  && pass "bounded 1000ms snapshot timeout" || fail "snapshot timeout missing"

for token in TYPE_ACCELEROMETER TYPE_GYROSCOPE TYPE_PROXIMITY TYPE_LIGHT \
             registerListener unregisterListener SENSOR_DELAY_NORMAL; do
  grep -q "$token" "$BRIDGE" \
    && pass "sensor source/lifecycle token $token" \
    || fail "missing sensor/lifecycle token $token"
done
grep -q 'CountDownLatch' "$BRIDGE" \
  && pass "bounded batch wait" || fail "missing bounded batch wait"
grep -q 'available.*false' "$BRIDGE" \
  && pass "unavailable shape exists" || fail "missing unavailable shape"

grep -q 'sample.type == Sensor.TYPE_LIGHT' "$BRIDGE" \
  && pass "light branch is explicit" || fail "light branch missing"
grep -q 'result.put("lux", values[0])' "$BRIDGE" \
  && pass "light uses lux" || fail "light lux contract missing"
grep -q 'sample.type == Sensor.TYPE_PROXIMITY' "$BRIDGE" \
  && pass "proximity branch is explicit" || fail "proximity branch missing"
grep -q 'result.put("value", values[0])' "$BRIDGE" \
  && pass "proximity uses value" || fail "proximity value contract missing"
grep -q 'result.put("maxRange", sample.sensor.getMaximumRange())' "$BRIDGE" \
  && pass "proximity includes maxRange" || fail "proximity maxRange contract missing"

LIGHT_BLOCK="$(sed -n '/sample.type == Sensor.TYPE_LIGHT/,/}/p' "$BRIDGE")"
if grep -q 'result.put("value"' <<<"$LIGHT_BLOCK"; then
  fail "light branch must not substitute value for lux"
else
  pass "light branch has no value substitution"
fi


if grep -Eq 'ForegroundService|BootReceiver|WorkManager|AlarmManager|startService|startForeground|SharedPreferences|SQLite|HttpURLConnection|WebSocket|nativeCommand|executeNative|runCapability|requestPermissions|POST_NOTIFICATIONS|ACCESS_.*LOCATION|PACKAGE_USAGE_STATS' "$BRIDGE"; then
  fail "PhysicalBridge contains forbidden capability or side effect"
else
  pass "PhysicalBridge has no forbidden capability or side effect"
fi

grep -q 'new PhysicalBridge' "$MAIN" && grep -q '"ElpisPhysical"' "$MAIN" \
  && pass "ElpisPhysical is registered" || fail "ElpisPhysical registration missing"

[[ -f "$MANIFEST" ]] \
  && pass "AndroidManifest.xml exists" \
  || fail "AndroidManifest.xml missing: $MANIFEST"
[[ -d "$GEN" ]] \
  && pass "generated Canary source directory exists" \
  || fail "generated Canary source directory missing: $GEN"
[[ -f "$GEN/PhysicalBridge.java" ]] \
  && pass "generated PhysicalBridge present" \
  || fail "generated PhysicalBridge missing: $GEN/PhysicalBridge.java"

if grep -Eq 'ACCESS_.*LOCATION|ACTIVITY_RECOGNITION|BLUETOOTH|READ_CALENDAR|WRITE_CALENDAR|BIND_.*SERVICE|BODY_SENSORS|BODY_SENSORS_BACKGROUND|FOREGROUND_SERVICE|RECEIVE_BOOT_COMPLETED|CAMERA|RECORD_AUDIO|SYSTEM_ALERT_WINDOW|SCHEDULE_EXACT_ALARM|REQUEST_INSTALL_PACKAGES|WRITE_SETTINGS' "$MANIFEST"; then
  fail "P2C.1 introduced a dangerous or special permission"
else
  pass "no P2C.1 dangerous/special permission"
fi

[[ "$FAIL" -eq 0 ]] || exit 1
echo "P2C.1 contract test PASSED"
