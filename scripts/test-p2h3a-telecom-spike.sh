#!/usr/bin/env bash
# P2H-3A Core-Telecom probe: source and generated-tree contract.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
ANDROID_ROOT="${1:-}"
SERVICE="$NATIVE/TelecomSpikeService.kt"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }
has() { grep -qF -- "$1" "$2"; }

for source in TelecomSpikeService.kt TelecomSpikeActivity.kt; do
  [[ -f "$NATIVE/$source" ]] && pass "present native/$source" || fail "missing native/$source"
done

# Standalone: the probe never reaches into the call loop, and the call loop never starts it.
if grep -Ev '^[[:space:]]*(\*|/\*|//)' "$SERVICE" | grep -Eq 'VoiceBridge|CallAudioBridge|setCommunicationDevice|setSpeakerphoneOn|evaluateJavascript'; then
  fail "probe touches the call loop or AudioManager routing"
else
  pass "probe stays out of the call loop and AudioManager routing"
fi
if grep -Eq 'TelecomSpike' "$NATIVE/MainActivity.java" "$NATIVE/VoiceBridge.java" "$NATIVE/CallAudioBridge.java" 2>/dev/null; then
  fail "the call loop references the probe"
else
  pass "the call loop does not reference the probe"
fi

has 'manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)' "$SERVICE" \
  && pass "registers with Telecom" || fail "registerAppWithTelecom missing"
has 'manager.addCall(' "$SERVICE" && pass "adds the call through CallsManager" || fail "addCall missing"
has 'ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL' "$SERVICE" \
  && pass "phoneCall foreground service type" || fail "phoneCall FGS type missing"
if grep -Fq 'FOREGROUND_SERVICE_TYPE_MICROPHONE' "$SERVICE"; then
  fail "probe uses the unapproved microphone FGS type"
else
  pass "probe uses phoneCall only"
fi
has 'requestEndpointChange(target)' "$SERVICE" && has 'availableEndpoints.collect' "$SERVICE" \
  && has 'currentCallEndpoint.collect' "$SERVICE" \
  && pass "routes through Telecom endpoints and logs them" || fail "endpoint routing/logging missing"
has 'NotificationCompat.CallStyle.forIncomingCall(' "$SERVICE" && has 'NotificationCompat.CallStyle.forOngoingCall(' "$SERVICE" \
  && pass "CallStyle for ringing and ongoing" || fail "CallStyle missing"
has 'MediaRecorder.AudioSource.VOICE_COMMUNICATION' "$SERVICE" && has 'allZeroBuffers=' "$SERVICE" \
  && pass "background recording is measured (frames, peak, all-zero buffers)" || fail "AudioRecord probe missing"
# r2: hang-up is observable in three stages, and local media never waits on Telecom.
python3 - "$SERVICE" <<'PY' && pass "hang-up is logged on arrival in onStartCommand, before it is queued" || fail "hang-up arrival not logged on the receive path"
import sys
src = open(sys.argv[1]).read()
body = src[src.index('override fun onStartCommand('):]
body = body[:body.index('\n    }\n')]
line = 'if (action == ACTION_HANGUP) log("hangup command received'
assert line in body and body.index(line) < body.index('commands.trySend(action)')
PY
has 'log("disconnect requested cause=' "$SERVICE" \
  && has 'log("disconnect result=$result elapsedMs=' "$SERVICE" \
  && pass "hang-up logged on arrival, at request and at result" || fail "hang-up stages not all logged"
has 'if (answered) DisconnectCause.LOCAL else DisconnectCause.REJECTED' "$SERVICE" \
  && pass "unanswered calls are REJECTED, answered ones LOCAL" || fail "disconnect cause not chosen by call state"
python3 - "$SERVICE" <<'PY' && pass "recording stops right after a successful disconnect" || fail "recording waits on the call scope"
import sys
src = open(sys.argv[1]).read()
i = src.index('if (result is CallControlResult.Success) {')
assert src.index('stopRecording("disconnect succeeded")', i) < src.index('return false', i)
PY
has 'watchers = launch {' "$SERVICE" && has 'stopWatchers("disconnect succeeded")' "$SERVICE" \
  && has 'stopWatchers("telecom onDisconnect")' "$SERVICE" \
  && pass "Telecom state collectors end with the call (else addCall never returns)" || fail "state collectors outlive the call"
has 'TEARDOWN_TIMEOUT_MS' "$SERVICE" && has 'callJob?.cancel()' "$SERVICE" && has 'finish("teardown watchdog")' "$SERVICE" \
  && pass "bounded teardown watchdog" || fail "no bounded teardown fallback"
if grep -Eq 'Process\.killProcess|exitProcess|System\.exit' "$SERVICE"; then
  fail "the probe must not kill its own process"
else
  pass "no process kill"
fi
has 'log("CallControlScope exited' "$SERVICE" && has 'log("stopForeground done")' "$SERVICE" \
  && has 'log("AudioRecord.release() done")' "$SERVICE" \
  && pass "scope exit, stopForeground and release are each logged" || fail "teardown steps not all logged"
has 'startForeground FAILED' "$SERVICE" && has 'addCall FAILED' "$SERVICE" && has 'registerAppWithTelecom FAILED' "$SERVICE" \
  && pass "platform refusals are logged, not crashes" || fail "refusals not logged"
has 'const val TAG = "ElpisTelecomSpike"' "$SERVICE" && pass "logcat tag ElpisTelecomSpike" || fail "log tag changed"

# BLUETOOTH_CONNECT (merged in by core-telecom, owner-approved 2026-10-24): asked only when
# a Bluetooth endpoint is wanted, never at startup; a denial leaves the call on earpiece/speaker.
ACTIVITY="$NATIVE/TelecomSpikeActivity.kt"
python3 - "$ACTIVITY" <<'PY' && pass "Nearby devices is requested only from the Bluetooth action" || fail "BLUETOOTH_CONNECT requested outside the Bluetooth action"
import sys
src = open(sys.argv[1]).read()
assert src.count('requestPermissions(') == 1
on_action = src[src.index('private fun onAction('):]
on_action = on_action[:on_action.index('\n    }\n')]
assert 'requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)' in on_action
assert 'action == TelecomSpikeService.ACTION_BLUETOOTH && needsNearbyDevices()' in on_action
create = src[src.index('override fun onCreate('):]
create = create[:create.index('\n    }\n')]
assert 'requestPermissions' not in create
PY
has 'log("Nearby devices denied: staying on the current earpiece/speaker endpoint")' "$ACTIVITY" \
  && has 'log("Bluetooth skipped: Nearby devices not granted; the call stays on earpiece/speaker")' "$SERVICE" \
  && pass "a denial degrades to earpiece/speaker without ending the call" || fail "no graceful Bluetooth denial path"
if grep -Ev '^[[:space:]]*(\*|/\*|//)' "$SERVICE" | grep -Fq 'requestPermissions'; then
  fail "the service must not request permissions"
else
  pass "the service never requests permissions"
fi

if [[ -n "$ANDROID_ROOT" ]]; then
  MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
  GRADLE="$ANDROID_ROOT/app/build.gradle"
  GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
  for permission in MANAGE_OWN_CALLS FOREGROUND_SERVICE_PHONE_CALL USE_FULL_SCREEN_INTENT; do
    [[ "$(grep -Fc "android.permission.$permission\"" "$MANIFEST")" -eq 1 ]] \
      && pass "$permission declared once" || fail "$permission declaration count is not one"
  done
  if grep -Fq 'android.permission.CALL_PHONE"' "$MANIFEST" || grep -Fq 'android.permission.READ_PHONE_STATE"' "$MANIFEST"; then
    fail "probe must not need SIM telephony permissions"
  else
    pass "no SIM telephony permissions"
  fi
  grep -Fq 'android:foregroundServiceType="phoneCall"' "$MANIFEST" \
    && pass "service declares phoneCall only" || fail "service FGS type is not phoneCall"
  if grep -Fq 'android.permission.FOREGROUND_SERVICE_MICROPHONE"' "$MANIFEST"; then
    fail "microphone FGS type is not approved"
  else
    pass "no microphone FGS permission"
  fi
  [[ "$(grep -Ec 'androidx\.core:core-telecom:' "$GRADLE")" -eq 1 ]] && grep -Fq 'androidx.core:core-telecom:1.1.0-rc01' "$GRADLE" \
    && pass "core-telecom 1.1.0-rc01 appears once" || fail "core-telecom dependency contract failed"
  [[ "$(grep -Ec '^[[:space:]]*compileSdkMinor[[:space:]]*=[[:space:]]*1' "$GRADLE")" -eq 1 ]] \
    && pass "compileSdkMinor = 1 (core-telecom 1.1 needs compileSdk 36.1)" || fail "compileSdkMinor missing or duplicated"
  for source in TelecomSpikeService.kt TelecomSpikeActivity.kt; do
    cmp -s "$NATIVE/$source" "$GEN_JAVA/$source" && pass "generated $source matches native/" || fail "generated $source missing or stale"
  done
fi

if [[ $FAIL -ne 0 ]]; then
  echo "P2H-3A telecom probe contract test FAILED" >&2
  exit 1
fi
echo "P2H-3A telecom probe contract test PASSED"
