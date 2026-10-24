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
has 'requestEndpointChange(target)' "$SERVICE" && has 'availableEndpoints.collect' "$SERVICE" \
  && has 'currentCallEndpoint.collect' "$SERVICE" \
  && pass "routes through Telecom endpoints and logs them" || fail "endpoint routing/logging missing"
has 'NotificationCompat.CallStyle.forIncomingCall(' "$SERVICE" && has 'NotificationCompat.CallStyle.forOngoingCall(' "$SERVICE" \
  && pass "CallStyle for ringing and ongoing" || fail "CallStyle missing"
has 'MediaRecorder.AudioSource.VOICE_COMMUNICATION' "$SERVICE" && has 'zeroReads=' "$SERVICE" \
  && pass "background recording is measured (frames, peak, all-zero reads)" || fail "AudioRecord probe missing"
has 'startForeground FAILED' "$SERVICE" && has 'addCall FAILED' "$SERVICE" && has 'registerAppWithTelecom FAILED' "$SERVICE" \
  && pass "platform refusals are logged, not crashes" || fail "refusals not logged"
has 'const val TAG = "ElpisTelecomSpike"' "$SERVICE" && pass "logcat tag ElpisTelecomSpike" || fail "log tag changed"

if [[ -n "$ANDROID_ROOT" ]]; then
  MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
  GRADLE="$ANDROID_ROOT/app/build.gradle"
  GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
  for permission in MANAGE_OWN_CALLS FOREGROUND_SERVICE_PHONE_CALL FOREGROUND_SERVICE_MICROPHONE USE_FULL_SCREEN_INTENT; do
    [[ "$(grep -Fc "android.permission.$permission\"" "$MANIFEST")" -eq 1 ]] \
      && pass "$permission declared once" || fail "$permission declaration count is not one"
  done
  if grep -Fq 'android.permission.CALL_PHONE"' "$MANIFEST" || grep -Fq 'android.permission.READ_PHONE_STATE"' "$MANIFEST"; then
    fail "probe must not need SIM telephony permissions"
  else
    pass "no SIM telephony permissions"
  fi
  grep -Fq 'android:foregroundServiceType="phoneCall|microphone"' "$MANIFEST" \
    && pass "service declares phoneCall|microphone" || fail "service FGS types missing"
  [[ "$(grep -Ec 'androidx\.core:core-telecom:' "$GRADLE")" -eq 1 ]] && grep -Fq 'androidx.core:core-telecom:1.0.1' "$GRADLE" \
    && pass "core-telecom 1.0.1 appears once" || fail "core-telecom dependency contract failed"
  for source in TelecomSpikeService.kt TelecomSpikeActivity.kt; do
    cmp -s "$NATIVE/$source" "$GEN_JAVA/$source" && pass "generated $source matches native/" || fail "generated $source missing or stale"
  done
fi

if [[ $FAIL -ne 0 ]]; then
  echo "P2H-3A telecom probe contract test FAILED" >&2
  exit 1
fi
echo "P2H-3A telecom probe contract test PASSED"
