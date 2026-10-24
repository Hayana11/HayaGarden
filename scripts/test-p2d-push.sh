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
grep -qF 'NotificationSupport.pollUrl(this, WAIT_S, call)' "$SERVICE" \
  && pass "long poll uses the ack-protocol URL with wait" || fail "long poll must use NotificationSupport.pollUrl(this, WAIT_S, call)"
grep -qF 'protocol=ack&wait=' "$SUPPORT" \
  && pass "poll URL speaks the ack protocol with wait" || fail "pollUrl must request protocol=ack with wait"
if grep -qE 'since=|readCursor|wakeNumber' "$SUPPORT" "$NATIVE/DeliveryLedger.java" "$SERVICE" "$NATIVE/NotificationPollWorker.java"; then
  fail "clients must not send a since cursor: acks are explicit per id"
else
  pass "no since cursor in clients (acks are explicit per id)"
fi
for client in "$SERVICE" "$NATIVE/NotificationPollWorker.java"; do
  grep -qF 'NotificationSupport.handlePayload' "$client" \
    && pass "$(basename "$client") delivers through handlePayload (show once, then ack)" \
    || fail "$(basename "$client") must deliver through NotificationSupport.handlePayload"
done
grep -qF 'DeliveryLedger.deliver' "$SUPPORT" && grep -qF 'synchronized (LOCK)' "$NATIVE/DeliveryLedger.java" \
  && pass "dedupe runs inside the shared ledger lock" || fail "dedupe must go through DeliveryLedger under its lock"
grep -qF '.commit()' "$SUPPORT" && ! grep -qF '.apply()' "$SUPPORT" \
  && pass "ledger writes are synchronous" || fail "ledger must persist with commit(), not apply()"

for client in "$SERVICE" "$NATIVE/NotificationPollWorker.java"; do
  grep -qF 'NotificationSupport.authorize(' "$client" \
    && pass "$(basename "$client") authenticates with the Health Bridge device credential" \
    || fail "$(basename "$client") must call NotificationSupport.authorize before polling"
done
grep -qF 'X-Health-Device-ID' "$SUPPORT" && grep -qF '"Bearer " + credential' "$SUPPORT" \
  && grep -qF 'new HealthCredentialStore(' "$SUPPORT" \
  && pass "push auth reuses HealthCredentialStore headers" || fail "push auth must reuse the Health Bridge credential headers"
awk '/public static void ack\(/,/^    }$/' "$SUPPORT" | grep -qF 'authorize(ctx, connection)' \
  && pass "ack is authenticated" || fail "ack must call authorize"
if grep -E 'protocol=ack' "$SUPPORT" >/dev/null && ! grep -qE '"\?wait=|API \+ "\?' "$SERVICE" "$NATIVE/NotificationPollWorker.java"; then
  pass "no legacy-protocol fallback in clients"
else
  fail "clients must not build a legacy (non-ack) poll URL"
fi
if grep -nE 'Log\.[a-z]+\(.*[Cc]redential|System\.out.*[Cc]redential' "$NATIVE"/*.java; then
  fail "a credential reaches a log statement"
else
  pass "no credential in log statements"
fi

LEDGER_OUT="$(mktemp -d)"
if javac -d "$LEDGER_OUT" "$NATIVE/DeliveryLedger.java" "$ROOT/scripts/jvm-tests/DeliveryLedgerTest.java" \
    && java -cp "$LEDGER_OUT" xyz.lovestyle.home.canary.DeliveryLedgerTest; then
  pass "DeliveryLedger JVM contract"
else
  fail "DeliveryLedger JVM contract"
fi
rm -rf "$LEDGER_OUT"
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
# The P2H-3A Core-Telecom probe (owner-approved 2026-10-24) starts its own phoneCall service.
if grep -lE 'startForegroundService|startService\(' "$NATIVE"/*.java "$NATIVE"/*.kt \
    | grep -vx "$STARTER" | grep -vqx "$NATIVE/TelecomSpikeService.kt"; then
  fail "only PushSupport may start a service"
else
  pass "PushSupport is the only service starter"
fi

if [[ -f "$MANIFEST" ]]; then
  for need in FOREGROUND_SERVICE FOREGROUND_SERVICE_REMOTE_MESSAGING RECEIVE_BOOT_COMPLETED; do
    grep -qF "android.permission.$need\"" "$MANIFEST" && pass "manifest has $need" || fail "manifest missing $need"
  done
  # RECORD_AUDIO is P2E's (call-page speech recognition), not P2D's.
  if grep -Eq 'android.permission.(FOREGROUND_SERVICE_DATA_SYNC|FOREGROUND_SERVICE_MICROPHONE|ACCESS_.*LOCATION|CAMERA)"' "$MANIFEST"; then
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
