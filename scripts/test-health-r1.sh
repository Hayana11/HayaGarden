#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/native/MainActivity.java"
NATIVE="$ROOT/native/NativeBridge.java"
VARIABLES_GRADLE="$ROOT/android/variables.gradle"
APP_GRADLE="$ROOT/android/app/build.gradle"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"

for required in \
  "$ROOT/native/HealthBridge.java" \
  "$ROOT/native/HealthStateStore.java" \
  "$ROOT/native/HealthCredentialStore.java" \
  "$ROOT/native/HealthConnectReader.kt" \
  "$ROOT/native/HealthSupport.java" \
  "$ROOT/native/HealthSyncWorker.java" \
  "$ROOT/native/HealthConfig.java" \
  "$ROOT/native/BuildInfo.java" \
  "$ROOT/native/HealthPermissionsRationaleActivity.java" \
  "$ROOT/scripts/apply-health-r1.sh"; do
  test -f "$required"
done

test -f "$VARIABLES_GRADLE"
test -f "$APP_GRADLE"
test -f "$MANIFEST"

python3 - "$MANIFEST" <<'PY'
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"

def local_name(tag):
    return tag.rsplit("}", 1)[-1]

root = ET.parse(sys.argv[1]).getroot()
print("AndroidManifest.xml XML parse PASS")

application = next(
    (node for node in root if local_name(node.tag) == "application"),
    None,
)
if application is None:
    raise SystemExit("missing application element")

def android_attr(node, name):
    return node.attrib.get(f"{{{ANDROID_NS}}}{name}")

children = list(application)
rationale = next(
    (
        node for node in children
        if local_name(node.tag) == "activity"
        and android_attr(node, "name") == ".HealthPermissionsRationaleActivity"
    ),
    None,
)
usage_alias = next(
    (
        node for node in children
        if local_name(node.tag) == "activity-alias"
        and android_attr(node, "name") == ".ViewPermissionUsageActivity"
    ),
    None,
)
if rationale is None:
    raise SystemExit("HealthPermissionsRationaleActivity is not under application")
if usage_alias is None:
    raise SystemExit("ViewPermissionUsageActivity is not under application")
if android_attr(usage_alias, "targetActivity") != ".HealthPermissionsRationaleActivity":
    raise SystemExit("ViewPermissionUsageActivity targetActivity mismatch")
print("Health rationale manifest structure PASS")
PY

MIN_SDK_ASSIGNMENTS="$(grep -Ec '^[[:space:]]*minSdkVersion[[:space:]]*=' "$VARIABLES_GRADLE" || true)"
if [[ "$MIN_SDK_ASSIGNMENTS" != "1" ]]; then
  echo "expected exactly one minSdkVersion authority; found $MIN_SDK_ASSIGNMENTS" >&2
  exit 1
fi
MIN_SDK_VALUE="$(sed -n -E 's/^[[:space:]]*minSdkVersion[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' "$VARIABLES_GRADLE" | head -n 1)"
if [[ ! "$MIN_SDK_VALUE" =~ ^[0-9]+$ ]] || (( MIN_SDK_VALUE < 26 )); then
  echo "Health Connect requires minSdkVersion >= 26; found $MIN_SDK_VALUE" >&2
  exit 1
fi
if grep -Eq '^[[:space:]]*minSdkVersion[[:space:]]*=[[:space:]]*[0-9]+' "$APP_GRADLE"; then
  echo "app/build.gradle must not declare a second numeric minSdkVersion authority" >&2
  exit 1
fi
grep -Eq '^[[:space:]]*minSdkVersion[[:space:]]+rootProject\.ext\.minSdkVersion' "$APP_GRADLE"
! grep -Eq 'tools:overrideLibrary[^>]*androidx\.health\.connect\.client|androidx\.health\.connect\.client[^>]*tools:overrideLibrary' "$MANIFEST"

count_dependency() {
  grep -F -c "implementation \"$1\"" "$APP_GRADLE" || true
}
WORK_RUNTIME_COUNT="$(count_dependency 'androidx.work:work-runtime:2.9.0')"
WORK_RUNTIME_KTX_COUNT="$(count_dependency 'androidx.work:work-runtime-ktx:2.9.0')"
HEALTH_CONNECT_COUNT="$(count_dependency 'androidx.health.connect:connect-client:1.1.0')"
GUAVA_COUNT="$(count_dependency 'com.google.guava:guava:31.1-android')"
if [[ "$WORK_RUNTIME_COUNT" != "1" \
   || "$WORK_RUNTIME_KTX_COUNT" != "1" \
   || "$HEALTH_CONNECT_COUNT" != "1" \
   || "$GUAVA_COUNT" != "1" ]]; then
  echo "unexpected Health R1 dependency counts: work=$WORK_RUNTIME_COUNT workKtx=$WORK_RUNTIME_KTX_COUNT healthConnect=$HEALTH_CONNECT_COUNT guava=$GUAVA_COUNT" >&2
  exit 1
fi
! grep -Fq 'com.google.guava:listenablefuture' "$APP_GRADLE"
! grep -Fq 'resolutionStrategy.force' "$APP_GRADLE"
! grep -Fq 'tools:overrideLibrary' "$MANIFEST"

grep -Fq '"ElpisNative"' "$MAIN"
grep -Fq '"ElpisNotifications"' "$MAIN"
grep -Fq '"ElpisPhysical"' "$MAIN"
grep -Fq '"ElpisActivity"' "$MAIN"
grep -Fq '"ElpisHealth"' "$MAIN"
grep -Fq 'HealthSupport.ensureScheduled' "$MAIN"
grep -Fq 'getBuildInfo' "$ROOT/native/HealthBridge.java"
! grep -Fq 'getBuildInfo' "$NATIVE"
grep -Fq 'getHealthState' "$ROOT/native/HealthBridge.java"
grep -Fq 'getHealthStatus' "$ROOT/native/HealthBridge.java"
grep -Fq 'syncNow' "$ROOT/native/HealthBridge.java"
grep -Fq 'enqueueNow' "$ROOT/native/HealthBridge.java"
grep -Fq 'getInstallId' "$ROOT/native/HealthBridge.java"
grep -Fq 'provisionDeviceCredential' "$ROOT/native/HealthBridge.java"
grep -Fq 'AndroidKeyStore' "$ROOT/native/HealthCredentialStore.java"
grep -Fq 'AES/GCM/NoPadding' "$ROOT/native/HealthCredentialStore.java"
grep -Fq 'cipher.init(Cipher.ENCRYPT_MODE, key)' "$ROOT/native/HealthCredentialStore.java"
grep -Fq 'cipher.getIV()' "$ROOT/native/HealthCredentialStore.java"
! grep -Fq 'cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec' "$ROOT/native/HealthCredentialStore.java"
grep -Fq 'setRandomizedEncryptionRequired(true)' "$ROOT/native/HealthCredentialStore.java"
grep -Fq 'public void requestHealthConnectPermissions()' "$ROOT/native/MainActivity.java"
grep -Fq 'healthPermissionLauncher.launch' "$ROOT/native/MainActivity.java"
grep -Fq 'PermissionController.createRequestPermissionResultContract()' "$ROOT/native/MainActivity.java"
grep -Fq 'new HealthBridge(this, healthStateStore)' "$ROOT/native/MainActivity.java"
grep -Fq '"ElpisHealth"' "$ROOT/native/MainActivity.java"
grep -Fq 'HealthPermissionsRationaleActivity.java' "$ROOT/scripts/apply-health-r1.sh"
grep -Fq 'HealthPermissionsRationaleActivity' "$ROOT/native/HealthPermissionsRationaleActivity.java"
grep -Fq 'requests READ access only' "$ROOT/native/HealthPermissionsRationaleActivity.java"
grep -Fq 'resting heart rate' "$ROOT/native/HealthPermissionsRationaleActivity.java"
grep -Fq "owner's personal Elpis health context" "$ROOT/native/HealthPermissionsRationaleActivity.java"
grep -Fq "owner's own HayaGarden server" "$ROOT/native/HealthPermissionsRationaleActivity.java"
grep -Fq 'does not request Health Connect WRITE permissions' "$ROOT/native/HealthPermissionsRationaleActivity.java"
grep -Fq 'revoke Health Connect access' "$ROOT/native/HealthPermissionsRationaleActivity.java"
grep -Fq 'HealthPermissionsRationaleActivity' "$MANIFEST"
grep -Fq 'androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE' "$MANIFEST"
grep -Fq 'android.intent.action.VIEW_PERMISSION_USAGE' "$MANIFEST"
grep -Fq 'android.intent.category.HEALTH_PERMISSIONS' "$MANIFEST"
grep -Fq 'android.permission.START_VIEW_PERMISSION_USAGE' "$MANIFEST"
grep -Fq 'android.permission.health.READ_HEART_RATE' "$MANIFEST"
grep -Fq 'android.permission.health.READ_RESTING_HEART_RATE' "$MANIFEST"
grep -Fq 'android.permission.health.READ_STEPS' "$MANIFEST"
grep -Fq 'android.permission.health.READ_SLEEP' "$MANIFEST"
grep -Fq 'android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND' "$MANIFEST"
! grep -Eq 'android\.permission\.health\.WRITE_|getWritePermission' "$MANIFEST" "$ROOT/native/HealthConnectReader.kt" "$ROOT/scripts/apply-health-r1.sh"
python3 - "$ROOT/native/MainActivity.java" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text()
start = source.index("public void requestHealthConnectPermissions()")
end = source.index("\n    private void requestHmsActivityPermissionIfNeeded()", start)
method = source[start:end]
assert "HealthConnectReader.permissionsForRequest" in method
assert "runOnUiThread" in method
assert "healthPermissionLauncher.launch" in method
assert method.index("runOnUiThread") < method.index("healthPermissionLauncher.launch")
PY
! grep -Fq 'EncryptedSharedPreferences' "$ROOT/native/HealthCredentialStore.java"
grep -Fq 'X-Health-Device-ID' "$ROOT/native/HealthSyncWorker.java"
grep -Fq 'auth_invalid' "$ROOT/native/HealthSyncWorker.java"
! grep -Fq 'INGEST_TOKEN' "$ROOT/native/HealthConfig.java"
! grep -Fq 'INGEST_TOKEN' "$ROOT/native/HealthSyncWorker.java"
! grep -Fq 'HEALTH_INGEST_TOKEN' "$ROOT/scripts/apply-health-r1.sh"
grep -Fq 'HealthConnectClient' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'getReadPermission(HeartRateRecord::class)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'getReadPermission(RestingHeartRateRecord::class)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'getReadPermission(StepsRecord::class)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'getReadPermission(SleepSessionRecord::class)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'READ_HEALTH_DATA_IN_BACKGROUND' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'minus(1, ChronoUnit.HOURS)' "$ROOT/native/HealthConnectReader.kt"
grep -Fq '"resting_heart_rate"' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'HEART_RATE_LIMIT = 300' "$ROOT/native/HealthConnectReader.kt"
grep -Fq 'RESTING_HEART_RATE_LIMIT = 30' "$ROOT/native/HealthConnectReader.kt"
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
echo "Health Bridge R1.1 contract PASS (minSdkVersion=$MIN_SDK_VALUE)"
