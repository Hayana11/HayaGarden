#!/usr/bin/env bash
# P2G: his incoming call notification, driven by the long poll's call delta.
# Contract: separate from message delivery (no DeliveryLedger, no ack), new
# high-importance channel, one fixed notification id, cancel on "call": null,
# tap opens /dash/call, known_call kept in memory only.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
ANDROID_ROOT="${1:-}"
CALL="$NATIVE/CallNotificationSupport.java"
STATE="$NATIVE/CallState.java"
SUPPORT="$NATIVE/NotificationSupport.java"
MAIN="$NATIVE/MainActivity.java"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

for f in "$CALL" "$STATE"; do
  [[ -f "$f" ]] && pass "present ${f#$ROOT/}" || fail "missing ${f#$ROOT/}"
done

for token in 'CHANNEL_ID = "fyodor_call_v1"' 'NotificationManager.IMPORTANCE_HIGH' \
             'NotificationCompat.CATEGORY_CALL' '.setOngoing(true)' 'NOTIFICATION_ID = 0xCA11' \
             'CALL_URL = "https://love-style.xyz/dash/call"' '.cancel(NOTIFICATION_ID)' \
             '.notify(NOTIFICATION_ID, builder.build())'; do
  grep -qF "$token" "$CALL" && pass "call notification has $token" || fail "call notification missing $token"
done
if grep -Eq 'DeliveryLedger\.|[^a-zA-Z_]ack\(|ACK_API|getSharedPreferences\(' "$CALL" "$STATE"; then
  fail "a call is not a delivery item: no ledger, no ack, nothing persisted"
else
  pass "call path has no ledger, no ack and no persistence"
fi
grep -qF 'CallNotificationSupport.pollQuery()' "$SUPPORT" \
  && pass "poll URL carries known_call" || fail "poll URL must carry known_call"
if grep -qF '"&known_call=" + known' "$CALL"; then
  pass "known_call comes from in-memory CallState"
else
  fail "known_call query missing"
fi
HANDLE=$(grep -n 'CallNotificationSupport.handleDelta(ctx, payload);' "$SUPPORT" | cut -d: -f1 | head -1)
EARLY=$(grep -n 'optBoolean("has_message", false)' "$SUPPORT" | cut -d: -f1 | head -1)
if [[ -n "$HANDLE" && -n "$EARLY" && "$HANDLE" -lt "$EARLY" ]]; then
  pass "call delta is applied before (and independent of) the message"
else
  fail "call delta must be applied before the message early return"
fi
grep -qF 'ack(ctx, id);' "$SUPPORT" && pass "message ack path unchanged" || fail "message ack path changed"
grep -qF 'CallNotificationSupport.EXTRA_OPEN_CALL' "$MAIN" \
  && grep -qF 'loadUrl(CallNotificationSupport.CALL_URL)' "$MAIN" \
  && pass "call tap opens the call page" || fail "call tap must open /dash/call"
grep -qF 'webView.loadUrl(NotificationSupport.CHAT_URL)' "$MAIN" \
  && pass "message tap still opens Chat" || fail "message tap regressed"

if grep -Eq 'ForegroundService|startService\(|BootReceiver|USE_FULL_SCREEN_INTENT|setFullScreenIntent' "$CALL" "$STATE"; then
  fail "call notification adds a service, receiver or full-screen intent"
else
  pass "no service, receiver or full-screen intent"
fi

if [[ -n "$ANDROID_ROOT" ]]; then
  GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
  for name in CallNotificationSupport CallState; do
    [[ -f "$GEN_JAVA/$name.java" ]] && pass "generated $name.java exists" || fail "generated $name.java missing"
  done
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
if javac -d "$OUT" "$STATE" "$ROOT/scripts/jvm-tests/CallStateTest.java" \
    && java -cp "$OUT" xyz.lovestyle.home.canary.CallStateTest; then
  pass "CallState JVM contract"
else
  fail "CallState JVM contract"
fi

exit "$FAIL"
