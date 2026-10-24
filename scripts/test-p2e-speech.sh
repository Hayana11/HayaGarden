#!/usr/bin/env bash
# P2E source and generated-tree contract locks.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
BRIDGE="$NATIVE/SpeechBridge.java"
MAIN="$NATIVE/MainActivity.java"
ANDROID_ROOT="${1:-$ROOT/android}"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

[[ -f "$BRIDGE" ]] && pass "present $BRIDGE" || fail "missing $BRIDGE"

grep -qF '"ElpisSpeech"' "$MAIN" && grep -qF 'new SpeechBridge(this, webView)' "$MAIN" \
  && pass "MainActivity registers ElpisSpeech" || fail "MainActivity must register ElpisSpeech"
grep -qF 'speechBridge.release()' "$MAIN" \
  && pass "microphone is released on pause" || fail "onPause must release the recognizer"
ON_RESUME="$(sed -n '/public void onResume/,/public void onPause/p' "$MAIN")"
printf '%s\n' "$ON_RESUME" | grep -qF 'speechBridge.resumed()' \
  && pass "page hears when the app comes back" || fail "onResume must tell the page (speechBridge.resumed())"
RELEASE="$(sed -n '/public void release()/,/^    }/p' "$BRIDGE")"
printf '%s\n' "$RELEASE" | grep -qF 'emit("error", null, "paused")' \
  && pass "pause reports the killed listen to the page" || fail "release() must emit error code paused"
grep -qF 'speechBridge.onPermissionResult(' "$MAIN" \
  && pass "permission result reaches the bridge" || fail "onRequestPermissionsResult must forward to SpeechBridge"

ON_CREATE="$(sed -n '/protected void onCreate/,/protected void onNewIntent/p' "$MAIN")"
if printf '%s\n' "$ON_CREATE" | grep -Eq 'RECORD_AUDIO|requestPermission\(\)'; then
  fail "the microphone permission must not be requested at startup"
else
  pass "no automatic microphone permission prompt"
fi
grep -qF 'EXTRA_PREFER_OFFLINE, false' "$BRIDGE" \
  && pass "recognition is not forced on-device" || fail "must not force on-device recognition"
if grep -Eq 'MediaRecorder|AudioRecord|ForegroundService|startService|FOREGROUND_SERVICE_MICROPHONE' "$BRIDGE"; then
  fail "bridge must not record raw audio or run in the background"
else
  pass "bridge only uses SpeechRecognizer"
fi
if grep -Eq 'HttpURLConnection|new URL\(|Log\.[a-z]+\(' "$BRIDGE"; then
  fail "bridge must not send or log what it hears"
else
  pass "bridge neither uploads nor logs transcripts"
fi

if [[ -f "$MANIFEST" ]]; then
  [[ "$(grep -Fc 'android.permission.RECORD_AUDIO"' "$MANIFEST")" -eq 1 ]] \
    && pass "RECORD_AUDIO declared once" || fail "RECORD_AUDIO must be declared exactly once"
  grep -qF 'android.speech.RecognitionService' "$MANIFEST" \
    && pass "speech services are visible (queries)" || fail "manifest missing RecognitionService query"
  if grep -Eq 'android.permission.FOREGROUND_SERVICE_MICROPHONE"' "$MANIFEST"; then
    fail "no background microphone service"
  fi
  [[ -f "$GEN_JAVA/SpeechBridge.java" ]] && pass "generated SpeechBridge.java exists" || fail "generated SpeechBridge.java missing"
fi

if [[ $FAIL -ne 0 ]]; then
  echo "P2E contract test FAILED" >&2
  exit 1
fi
echo "P2E contract test PASSED"
