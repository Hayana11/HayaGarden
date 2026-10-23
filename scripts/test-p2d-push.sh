#!/usr/bin/env bash
# P2D source and generated-tree contract locks.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
SERVICE="$NATIVE/PushService.java"
RECEIVER="$NATIVE/PushBootReceiver.java"
STARTER="$NATIVE/PushSupport.java"
SUPPORT="$NATIVE/NotificationSupport.java"
ANDROID_ROOT="${1:-$ROOT/android}"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

for file in "$SERVICE" "$RECEIVER" "$STARTER"; do
  [[ -f "$file" ]] && pass "present $file" || fail "missing $file"
done

grep -qF 'FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING' "$SERVICE" \
  && pass "service type is remoteMessaging" || fail "service must use remoteMessaging type"
if grep -qE 'FOREGROUND_SERVICE_TYPE_(DATA_SYNC|LOCATION|MEDIA_PROJECTION|MICROPHONE|CAMERA)' "$SERVICE"; then
  fail "service must not use a time-limited or sensitive foreground type"
fi
grep -qF 'NotificationSupport.API + "?wait="' "$SERVICE" \
  && pass "long poll uses the P2B endpoint with wait" || fail "long poll must reuse NotificationSupport.API with ?wait="
grep -qF 'NotificationSupport.showNotification' "$SERVICE" \
  && pass "display goes through NotificationSupport" || fail "service must display via NotificationSupport"
grep -qF 'MIN_CYCLE_MS' "$SERVICE" \
  && pass "service has a minimum cycle guard" || fail "service must not spin against a server that ignores wait"
if grep -qiE '"command"|screenshot|ScreenCapture|Pocket|location|AppTracker|setRequestMethod\("(POST|PUT|PATCH|DELETE)"\)' "$SERVICE"; then
  fail "service contains a forbidden remote-action capability"
else
  pass "service is display-only"
fi
grep -qF 'canPostNotifications' "$RECEIVER" && grep -qF 'ensurePollingScheduled' "$RECEIVER" \
  && pass "boot receiver is gated on notification permission" || fail "boot receiver must gate on canPostNotifications"

grep -qF 'canPostNotifications' "$STARTER" && grep -qF 'startForegroundService' "$STARTER" \
  && pass "service start is gated on notification permission" || fail "PushSupport must gate the start on canPostNotifications"
grep -qF 'PushSupport.ensureRunning(ctx);' "$SUPPORT" \
  && pass "NotificationSupport starts the push service" || fail "NotificationSupport.ensurePollingScheduled must call PushSupport.ensureRunning"
if grep -lE 'startForegroundService|startService\(' "$NATIVE"/*.java | grep -vqx "$STARTER"; then
  fail "only PushSupport may start a service"
else
  pass "PushSupport is the only service starter"
fi

if [[ -f "$MANIFEST" ]]; then
  for need in FOREGROUND_SERVICE FOREGROUND_SERVICE_REMOTE_MESSAGING RECEIVE_BOOT_COMPLETED; do
    grep -qF "android.permission.$need\"" "$MANIFEST" && pass "manifest has $need" || fail "manifest missing $need"
  done
  if grep -Eq 'android.permission.(FOREGROUND_SERVICE_DATA_SYNC|ACCESS_.*LOCATION|CAMERA|RECORD_AUDIO)"' "$MANIFEST"; then
    fail "manifest contains a permission P2D does not own"
  fi
  grep -qF 'android:foregroundServiceType="remoteMessaging"' "$MANIFEST" \
    && pass "manifest declares remoteMessaging service" || fail "manifest missing PushService declaration"
  [[ "$(grep -Fc 'android:name=".PushService"' "$MANIFEST")" -eq 1 ]] \
    && pass "PushService declared once" || fail "PushService must be declared exactly once"
  [[ "$(grep -Fc 'android:name=".PushBootReceiver"' "$MANIFEST")" -eq 1 ]] \
    && pass "PushBootReceiver declared once" || fail "PushBootReceiver must be declared exactly once"
  for name in PushService PushBootReceiver PushSupport; do
    [[ -f "$GEN_JAVA/$name.java" ]] && pass "generated $name.java exists" || fail "generated $name.java missing"
  done
fi

if [[ $FAIL -ne 0 ]]; then
  echo "P2D contract test FAILED" >&2
  exit 1
fi
echo "P2D contract test PASSED"
