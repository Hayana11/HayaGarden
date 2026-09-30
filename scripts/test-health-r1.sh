#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/native/MainActivity.java"
NATIVE="$ROOT/native/NativeBridge.java"

for required in \
  "$ROOT/native/HealthBridge.java" \
  "$ROOT/native/HealthStateStore.java" \
  "$ROOT/native/HealthConnectReader.kt" \
  "$ROOT/native/HealthSupport.java" \
  "$ROOT/native/HealthSyncWorker.java" \
  "$ROOT/native/HealthConfig.java" \
  "$ROOT/native/BuildInfo.java" \
  "$ROOT/scripts/apply-health-r1.sh"; do
  test -f "$required"
done

grep -Fq '"ElpisNative"' "$MAIN"
grep -Fq '"ElpisNotifications"' "$MAIN"
grep -Fq '"ElpisPhysical"' "$MAIN"
grep -Fq '"ElpisActivity"' "$MAIN"
grep -Fq '"ElpisHealth"' "$MAIN"
grep -Fq 'HealthSupport.ensureScheduled' "$MAIN"
grep -Fq 'getBuildInfo' "$NATIVE"
grep -Fq 'getHealthState' "$ROOT/native/HealthBridge.java"
grep -Fq 'getHealthStatus' "$ROOT/native/HealthBridge.java"
grep -Fq 'syncNow' "$ROOT/native/HealthBridge.java"
grep -Fq 'enqueueNow' "$ROOT/native/HealthBridge.java"
grep -Fq 'HealthConnectClient' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'READ_HEART_RATE' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'READ_STEPS' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'READ_SLEEP' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'READ_HEALTH_DATA_IN_BACKGROUND' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'sourceRecordId' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'ExistingPeriodicWorkPolicy.KEEP' "$ROOT/native/HealthSupport.java"
grep -Fq 'HEALTH_INGEST_TOKEN' "$ROOT/scripts/apply-health-r1.sh"
grep -Fq 'HealthBridge(this, healthStateStore)' "$MAIN"
if grep -Fq 'new HealthBridge(physicalStateStore' "$MAIN"; then
  echo "health bridge must remain independent from physical bridge" >&2
  exit 1
fi
echo "Health Bridge R1 contract PASS"
