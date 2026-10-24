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

grep -qF 'android.permission.VIBRATE' "$ROOT/scripts/apply-p2b-native-notifications.sh" \
  && pass "P2B apply declares VIBRATE for the call vibration" || fail "P2B apply must add android.permission.VIBRATE"
for f in "$CALL" "$STATE"; do
  [[ -f "$f" ]] && pass "present ${f#$ROOT/}" || fail "missing ${f#$ROOT/}"
done

for token in 'CHANNEL_ID = "fyodor_call_v3"' 'NotificationManager.IMPORTANCE_HIGH' \
             'NotificationCompat.CATEGORY_CALL' '.setOngoing(true)' 'NOTIFICATION_ID = 0xCA11' \
             'CALL_URL = "https://love-style.xyz/dash/call"' '.cancel(NOTIFICATION_ID)' \
             '.notify(NOTIFICATION_ID, notification)' \
             'notification.flags |= Notification.FLAG_INSISTENT' \
             'RingtoneManager.TYPE_RINGTONE' 'AudioAttributes.USAGE_NOTIFICATION_RINGTONE' \
             'channel.enableVibration(false)' '.setTimeoutAfter(RING_CAP_MS)' \
             '"fyodor_call_v1", "fyodor_call_v2"' 'manager.deleteNotificationChannel(retired)' \
             'VibrationEffect.createWaveform(VIBRATION, 0)' 'CallState.endRingIfCurrent(token)' \
             'MAIN.postDelayed(' 'long token = CallState.beginRing();'; do
  grep -qF "$token" "$CALL" && pass "call notification has $token" || fail "call notification missing $token"
done
if grep -Eq 'DeliveryLedger\.|[^a-zA-Z_]ack\(|ACK_API|getSharedPreferences\(' "$CALL" "$STATE"; then
  fail "a call is not a delivery item: no ledger, no ack, nothing persisted"
else
  pass "call path has no ledger, no ack and no persistence"
fi
grep -qF 'CallNotificationSupport.pollQuery(call)' "$SUPPORT" \
  && pass "poll URL carries known_call from the request's snapshot" || fail "poll URL must carry known_call from the request's snapshot"
if grep -qF '"&known_call=" + known' "$CALL" && grep -qF 'call.known' "$CALL"; then
  pass "known_call comes from the in-memory CallState snapshot"
else
  fail "known_call query must come from the CallState snapshot"
fi
grep -qF 'CallState.apply(sentWith, true, id)' "$CALL" \
  && pass "call delta is applied against the request's snapshot (revision fence)" \
  || fail "call delta must be applied through CallState.apply(snapshot, ...)"
# Each poller snapshots before building the URL and hands that same snapshot to
# its response; a snapshot read at handlePayload time would fence nothing.
for client in "$NATIVE/PushService.java" "$NATIVE/NotificationPollWorker.java"; do
  name="$(basename "$client")"
  SNAP=$(grep -n 'CallState.Snapshot call = CallState.snapshot();' "$client" | cut -d: -f1 | head -1 || true)
  URLL=$(grep -n 'NotificationSupport.pollUrl(.*, call)' "$client" | cut -d: -f1 | head -1 || true)
  if [[ -n "$SNAP" && -n "$URLL" && "$SNAP" -lt "$URLL" ]] \
      && grep -qF 'NotificationSupport.handlePayload(getApplicationContext(), payload, call);' "$client"; then
    pass "$name: snapshot taken before the request and passed to its response"
  else
    fail "$name: must snapshot CallState before the request and pass it to handlePayload"
  fi
done
if grep -n 'CallState.snapshot()' "$SUPPORT" "$CALL" >/dev/null; then
  fail "the response path must not take its own snapshot"
else
  pass "the response path never re-reads the snapshot"
fi
HANDLE=$(grep -n '^ *CallNotificationSupport.handleDelta(ctx, payload, call);$' "$SUPPORT" | cut -d: -f1 | head -1 || true)
EARLY=$(grep -n 'optBoolean("has_message", false)' "$SUPPORT" | cut -d: -f1 | head -1 || true)
if [[ -n "$HANDLE" && -n "$EARLY" && "$HANDLE" -lt "$EARLY" ]] \
    && grep -qF 'public static void handleDelta(' "$CALL"; then
  pass "call delta (void, possibly stale) is applied before and independent of the message"
else
  fail "call delta must be a void statement before the message early return"
fi
grep -qF 'ack(ctx, id);' "$SUPPORT" && pass "message ack path unchanged" || fail "message ack path changed"
# Vibration is app-owned (a channel pattern plays once on ColorOS): the channel
# never vibrates, SHOW starts a repeating Vibrator, every stop path cancels it.
if grep -qF 'setVibrationPattern' "$CALL"; then
  fail "the channel must not carry a vibration pattern (app-owned vibrator only)"
else
  pass "no channel vibration pattern: one vibration authority"
fi
SHOW_BODY=$(sed -n '/private static void show(Context ctx, String title)/,/^    }/p' "$CALL")
grep -qF 'startVibrating(ctx);' <<<"$SHOW_BODY" \
  && pass "showing the call starts the repeating vibration" || fail "show() must call startVibrating(ctx)"
DELTA_BODY=$(sed -n '/public static void handleDelta(/,/^    }/p' "$CALL")
if grep -qF 'stopRinging(ctx);' <<<"$DELTA_BODY" && ! grep -qF '.cancel(NOTIFICATION_ID)' <<<"$DELTA_BODY"; then
  pass "a withdrawal stops notification and vibration together"
else
  fail "the CANCEL delta must go through stopRinging(ctx)"
fi
STOP_BODY=$(sed -n '/public static void stopRinging(Context ctx)/,/^    }/p' "$CALL")
for need in '.cancel(NOTIFICATION_ID)' 'CallState.endRing();' 'vibrator.cancel();'; do
  grep -qF "$need" <<<"$STOP_BODY" && pass "stopRinging does $need" || fail "stopRinging must do $need"
done
VIB_BODY=$(sed -n '/static void startVibrating(Context ctx)/,/^    }/p' "$CALL")
grep -qF 'if (quiet(ctx))' <<<"$VIB_BODY" \
  && pass "vibration honors silent mode and Do Not Disturb" || fail "startVibrating must skip when quiet(ctx)"
# Insistent ringing must always end: a hard cap no longer than the server's
# 60 s ring plus a little slack, and a tap on the notification stops it.
CAP=$(sed -n -E 's/.*RING_CAP_MS = ([0-9_]+)L;.*/\1/p' "$CALL" | tr -d _ || true)
if [[ -n "$CAP" && "$CAP" -ge 60000 && "$CAP" -le 90000 ]]; then
  pass "insistent ring is capped at ${CAP} ms"
else
  fail "insistent ring needs RING_CAP_MS between 60 and 90 s (got '${CAP}')"
fi
STOP=$(grep -n 'CallNotificationSupport.stopRinging(this);' "$MAIN" | cut -d: -f1 | head -1 || true)
LOAD=$(grep -n 'loadUrl(CallNotificationSupport.CALL_URL)' "$MAIN" | cut -d: -f1 | head -1 || true)
if [[ -n "$STOP" && -n "$LOAD" && "$STOP" -lt "$LOAD" ]]; then
  pass "opening the call page from the notification stops the ringing"
else
  fail "tapping the call notification must stop the ringing"
fi
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
  GEN_MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
  [[ "$(grep -Fc 'android.permission.VIBRATE"' "$GEN_MANIFEST" 2>/dev/null || true)" -eq 1 ]] \
    && pass "generated manifest declares VIBRATE once (call vibration)" \
    || fail "generated manifest must declare android.permission.VIBRATE exactly once"
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
