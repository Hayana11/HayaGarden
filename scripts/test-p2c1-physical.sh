#!/usr/bin/env bash
# P2C.1 foreground-live cache and generated-tree contract locks.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STORE="$ROOT/native/PhysicalStateStore.java"
BRIDGE="$ROOT/native/PhysicalBridge.java"
MAIN="$ROOT/native/MainActivity.java"
ANDROID_ROOT="${1:-$ROOT/android}"
GEN="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }
require_file() { [[ -f "$1" ]] && pass "present $1" || fail "missing $1"; }

for file in "$STORE" "$BRIDGE" "$MAIN"; do
  require_file "$file"
done

if grep -qF 'import android.os.BatteryManager;' "$STORE"; then
  pass "PhysicalStateStore uses android.os.BatteryManager"
else
  fail "PhysicalStateStore must import android.os.BatteryManager"
fi
if grep -qF 'import android.hardware.BatteryManager;' "$STORE"; then
  fail "PhysicalStateStore must not import android.hardware.BatteryManager"
else
  pass "PhysicalStateStore has no android.hardware.BatteryManager import"
fi

[[ "$(grep -c '@JavascriptInterface' "$BRIDGE")" -eq 1 ]] \
  && pass "ElpisPhysical exposes exactly one JS method" \
  || fail "ElpisPhysical must expose exactly one JS method"
grep -q 'public String getPhysicalState()' "$BRIDGE" \
  && pass "getPhysicalState exists" || fail "getPhysicalState missing"

if grep -Eq 'getLatestPhysicalState|startPhysicalMonitoring|stopPhysicalMonitoring|watchPhysicalState|nativeCommand|getRealityContext' "$BRIDGE"; then
  fail "ElpisPhysical exposes a forbidden extra public surface"
else
  pass "ElpisPhysical public surface is narrow"
fi

for token in 'TYPE_ACCELEROMETER' 'TYPE_GYROSCOPE' 'TYPE_PROXIMITY' 'TYPE_LIGHT' \
             'registerListener' 'unregisterListener' 'SENSOR_DELAY_NORMAL'; do
  grep -q "$token" "$STORE" \
    && pass "store contains $token" \
    || fail "store missing $token"
done
grep -q 'public void start()' "$STORE" \
  && grep -q 'public void stop()' "$STORE" \
  && pass "store owns start/stop" \
  || fail "store start/stop missing"
grep -q 'synchronized (lock)' "$STORE" \
  && pass "store state access is synchronized" \
  || fail "store synchronization missing"

for token in 'Intent.ACTION_BATTERY_CHANGED' \
             'BatteryManager.EXTRA_LEVEL' 'BatteryManager.EXTRA_SCALE' 'BatteryManager.EXTRA_STATUS' \
             'BatteryManager.BATTERY_STATUS_CHARGING' 'BatteryManager.BATTERY_STATUS_FULL' \
             'registerReceiver' 'unregisterReceiver' 'batteryReceiverRegistered'; do
  grep -q "$token" "$STORE" \
    && pass "battery contract contains $token" \
    || fail "battery contract missing $token"
done

grep -q 'if (batteryReceiverRegistered)' "$STORE" \
  && pass "battery receiver has a registration guard" \
  || fail "battery registration guard missing"
grep -q '!batteryReceiverRegistered' "$STORE" \
  && pass "battery receiver has an unregistration guard" \
  || fail "battery unregistration guard missing"

START_BLOCK="$(sed -n '/public void start()/,/public void stop()/p' "$STORE")"
STOP_BLOCK="$(sed -n '/public void stop()/,/public Snapshot snapshot()/p' "$STORE")"
printf '%s\n' "$START_BLOCK" | grep -q 'registerBatteryReceiverLocked' \
  && pass "battery receiver registers in foreground start" \
  || fail "battery receiver registration is not in start path"
printf '%s\n' "$STOP_BLOCK" | grep -q 'unregisterBatteryReceiverLocked' \
  && pass "battery receiver unregisters in foreground stop" \
  || fail "battery receiver cleanup is not in stop path"

RESUME_BLOCK="$(sed -n '/public void onResume/,/public void onPause/p' "$MAIN")"
PAUSE_BLOCK="$(sed -n '/public void onPause/,/protected void onNewIntent/p' "$MAIN")"
printf '%s\n' "$RESUME_BLOCK" | grep -q 'physicalStateStore.start()' \
  && pass "onResume starts physical collection" \
  || fail "onResume start hook missing"
printf '%s\n' "$PAUSE_BLOCK" | grep -q 'physicalStateStore.stop()' \
  && pass "onPause stops physical collection" \
  || fail "onPause stop hook missing"
if grep -Eq 'reload\(|clearCache|clearHistory' "$MAIN"; then
  fail "MainActivity adds a WebView reload or cache reset"
else
  pass "MainActivity keeps no-reload lifecycle behavior"
fi

GETTER="$(awk '/public String getPhysicalState\(\)/,/^    \}/' "$BRIDGE")"
if printf '%s\n' "$GETTER" | grep -Eq 'registerListener|unregisterListener|CountDownLatch|await|sleep|Timer|HttpURLConnection|WebSocket|SharedPreferences|SQLite|openConnection|getInputStream'; then
  fail "getPhysicalState is not a read-only cache getter"
else
  pass "getPhysicalState is a non-blocking read-only cache getter"
fi

SNAPSHOT_BLOCK="$(sed -n '/public Snapshot snapshot()/,/private void refreshAvailabilityLocked/p' "$STORE")"
if printf '%s\n' "$SNAPSHOT_BLOCK" | grep -Eq 'refreshBattery|registerReceiver|unregisterReceiver|BatteryManager|NativeBridge|getBattery'; then
  fail "snapshot is not a RAM-only battery cache copy"
else
  pass "snapshot remains a RAM-only battery cache copy"
fi

for token in schemaVersion monitoring available ready sampledAt updatedAt lux maxRange; do
  grep -q "$token" "$BRIDGE" \
    && pass "schema field $token" \
    || fail "schema field $token missing"
done
grep -q 'result.put("value", sensor.value)' "$BRIDGE" \
  && pass "proximity uses value" || fail "proximity value missing"
grep -q 'result.put("maxRange", sensor.maxRange)' "$BRIDGE" \
  && pass "proximity uses maxRange" || fail "proximity maxRange missing"

if grep -Eq 'ForegroundService|startForegroundService|startService\(|BootReceiver|AlarmManager|WorkManager|Handler|Timer|scheduledExecutor|SharedPreferences|SQLite|HttpURLConnection|WebSocket|MCP|Firebase|GMS' "$STORE" "$BRIDGE" "$MAIN"; then
  fail "physical layer contains a forbidden background/network/persistence capability"
else
  pass "physical layer has no forbidden background/network/persistence capability"
fi

require_file "$MANIFEST"
if [[ -f "$MANIFEST" ]]; then
  if grep -Eq 'android.permission.(ACCESS_.*LOCATION|ACTIVITY_RECOGNITION|BLUETOOTH|READ_CALENDAR|WRITE_CALENDAR|BODY_SENSORS|BODY_SENSORS_BACKGROUND|FOREGROUND_SERVICE|RECEIVE_BOOT_COMPLETED|CAMERA|RECORD_AUDIO|SYSTEM_ALERT_WINDOW|SCHEDULE_EXACT_ALARM|REQUEST_INSTALL_PACKAGES|WRITE_SETTINGS)' "$MANIFEST"; then
    fail "P2C.1 introduced a dangerous or special permission"
  else
    pass "no P2C.1 dangerous/special permission"
  fi
else
  fail "cannot evaluate permissions because AndroidManifest.xml is missing"
fi

require_file "$GEN/MainActivity.java"
require_file "$GEN/PhysicalBridge.java"
require_file "$GEN/PhysicalStateStore.java"

[[ "$FAIL" -eq 0 ]] || exit 1
echo "P2C.1 foreground physical cache contract PASSED"
