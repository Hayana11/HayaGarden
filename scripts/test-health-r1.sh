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
grep -Fq 'getReadPermission(HeartRateRecord::class)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'getReadPermission(StepsRecord::class)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'getReadPermission(SleepSessionRecord::class)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'READ_HEALTH_DATA_IN_BACKGROUND' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'HEART_RATE_LIMIT = 300' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'STEPS_LIMIT = 100' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'SLEEP_LIMIT = 100' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'AggregateRequest' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'StepsRecord.COUNT_TOTAL' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'permissionState' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'ZoneId.systemDefault' "$ROOT/native/HealthConnectReader.kt"
! grep -Fq 'substring(0, 10)' "$ROOT/native/HealthConnectReader.kt"
! grep -Fq 'HEALTH_INGEST_TOKEN' "$ROOT/scripts/apply-health-r1.sh"
grep -Fq 'BackoffPolicy.EXPONENTIAL' "$ROOT/native/HealthSupport.java"
grep -Fq 'Result.retry()' "$ROOT/native/HealthSyncWorker.java"
grep -Fq 'sourceRecordId' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'ExistingPeriodicWorkPolicy.KEEP' "$ROOT/native/HealthSupport.java"
grep -Fq 'HealthConnectReader.permissionsForRequest' "$MAIN"
if grep -Fq 'new HealthBridge(physicalStateStore' "$MAIN"; then
  echo "health bridge must remain independent from physical bridge" >&2
  exit 1
fi
echo "Health Bridge R1.1 contract PASS"
