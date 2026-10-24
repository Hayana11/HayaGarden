#!/usr/bin/env bash
# P2H-1 source/generated-tree and pure JVM contract tests for native call audio.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
ANDROID_ROOT="${1:-}"
BRIDGE="$NATIVE/CallAudioBridge.java"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }
has() { grep -qF -- "$1" "$2"; }

for source in CallAudioBridge.java CallAudioUrls.java; do
  [[ -f "$NATIVE/$source" ]] && pass "present native/$source" || fail "missing native/$source"
done

has 'webView.addJavascriptInterface(callAudioBridge, "ElpisCallAudio")' "$NATIVE/MainActivity.java" \
  && pass "MainActivity registers ElpisCallAudio" || fail "ElpisCallAudio registration missing"
has 'callAudioBridge.close()' "$NATIVE/MainActivity.java" \
  && pass "call audio closes on destroy" || fail "onDestroy close missing"
has 'webView.addJavascriptInterface(voiceBridge, "ElpisVoice")' "$NATIVE/MainActivity.java" \
  && pass "ElpisVoice still separate (mic stays in VoiceBridge)" || fail "ElpisVoice registration changed"
if grep -Eq 'ExoPlayer|AudioManager|setCommunicationDevice' "$NATIVE/VoiceBridge.java"; then
  fail "playback/routing leaked into VoiceBridge"
else
  pass "VoiceBridge has no playback or routing"
fi

# Security: the page names a line; native builds the URL and attaches the cookie.
has 'public void play(String callId, double seq)' "$BRIDGE" \
  && pass "play takes callId and seq only" || fail "play signature changed"
has 'CallAudioUrls.audioUrl(callId, (long) seq)' "$BRIDGE" \
  && pass "play URL comes from CallAudioUrls" || fail "play does not build its URL natively"
has 'public static final String ORIGIN = "https://love-style.xyz";' "$NATIVE/CallAudioUrls.java" \
  && pass "fixed host" || fail "host is not fixed"
has 'CookieManager.getInstance().getCookie(CallAudioUrls.ORIGIN)' "$BRIDGE" \
  && pass "cookie read natively for the fixed origin" || fail "native cookie read missing"
has '.setAllowCrossProtocolRedirects(false)' "$BRIDGE" \
  && pass "no cross-protocol redirects" || fail "cross-protocol redirects not disabled"
if grep -Eq 'Uri\.parse\(|MediaItem\.fromUri\((callId|seq)' "$BRIDGE"; then
  fail "bridge parses a page-supplied URI"
else
  pass "bridge never parses a page-supplied URI"
fi
n_js="$(grep -c '@JavascriptInterface' "$BRIDGE" || true)"
[[ "$n_js" -eq 6 ]] && pass "exactly six JS methods (isAvailable begin play stop setSpeaker end)" \
  || fail "unexpected JS surface: $n_js @JavascriptInterface methods"
if grep -Eq 'getCookie\([^)]*\)[^;]*emit|"cookie"' "$BRIDGE"; then
  fail "cookie may be emitted to the page"
else
  pass "cookie never emitted to the page"
fi

# Audio path.
has 'C.USAGE_VOICE_COMMUNICATION' "$BRIDGE" && has 'C.AUDIO_CONTENT_TYPE_SPEECH' "$BRIDGE" \
  && pass "voice-communication speech attributes" || fail "audio attributes wrong"
has '.setAudioAttributes(attributes, /* handleAudioFocus= */ false)' "$BRIDGE" \
  && pass "ExoPlayer does not handle audio focus" || fail "handleAudioFocus must be false"
has 'audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION)' "$BRIDGE" \
  && pass "begin enters MODE_IN_COMMUNICATION" || fail "communication mode missing"
has 'AudioDeviceInfo.TYPE_BUILTIN_EARPIECE' "$BRIDGE" && has 'AudioDeviceInfo.TYPE_BUILTIN_SPEAKER' "$BRIDGE" \
  && has 'audioManager.setCommunicationDevice(device)' "$BRIDGE" \
  && pass "earpiece/speaker via setCommunicationDevice" || fail "communication device routing missing"
has 'audioManager.clearCommunicationDevice()' "$BRIDGE" && has 'audioManager.setMode(AudioManager.MODE_NORMAL)' "$BRIDGE" \
  && pass "end clears the device and restores MODE_NORMAL" || fail "end does not restore routing"
python3 - "$BRIDGE" <<'PY' && pass "end clears the device before MODE_NORMAL" || fail "end restores mode before clearing the device"
import sys
src = open(sys.argv[1]).read()
body = src[src.index('private void endOnMain()'):]
body = body[:body.index('\n    }\n')]
assert body.index('clearCommunicationDevice()') < body.index('MODE_NORMAL')
PY
python3 - "$BRIDGE" <<'PY' && pass "begin defaults to earpiece" || fail "begin does not default to earpiece"
import sys
src = open(sys.argv[1]).read()
body = src[src.index('private void beginOnMain()'):]
body = body[:body.index('\n    }\n')]
assert 'wantSpeaker = false;' in body and body.index('wantSpeaker = false;') < body.index('applyRoute();')
PY
python3 - "$BRIDGE" <<'PY' && pass "setSpeaker reroutes without touching the player" || fail "setSpeaker touches playback"
import sys
src = open(sys.argv[1]).read()
body = src[src.index('public void setSpeaker(boolean on)'):]
body = body[:body.index('\n    }\n')]
assert 'applyRoute();' in body
for word in ('player', 'stop', 'prepare', 'setMediaItem'):
    assert word not in body, word
PY
has 'accepted = audioManager.setCommunicationDevice(device);' "$BRIDGE" \
  && has 'fallBackToSpeakerphone(speaker);' "$BRIDGE" && has 'audioManager.setSpeakerphoneOn(speaker);' "$BRIDGE" \
  && pass "a refused or ignored device choice falls back to setSpeakerphoneOn" || fail "route result unchecked or no fallback"
has 'if (!want.equals(now))' "$BRIDGE" && has 'ROUTE_CHECK_MS' "$BRIDGE" \
  && pass "the applied route is verified after the platform had time" || fail "applied route never verified"
has 'audioManager.setSpeakerphoneOn(false);  // undoes a fallback too' "$BRIDGE" \
  && pass "end also undoes a speakerphone fallback" || fail "end leaves a speakerphone fallback on"
has 'addOnCommunicationDeviceChangedListener' "$BRIDGE" \
  && pass "route events follow the platform's device changes" || fail "no device-change listener"
for event in '"started"' '"ended"' '"error"' '"route"'; do
  has "$event" "$BRIDGE" && pass "emits $event" || fail "does not emit $event"
done
has 'window.__elpisCallAudio && window.__elpisCallAudio(' "$BRIDGE" \
  && pass "events go to window.__elpisCallAudio" || fail "event sink changed"

if [[ -n "$ANDROID_ROOT" ]]; then
  MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
  GRADLE="$ANDROID_ROOT/app/build.gradle"
  GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
  [[ "$(grep -Fc 'android.permission.MODIFY_AUDIO_SETTINGS"' "$MANIFEST")" -eq 1 ]] \
    && pass "MODIFY_AUDIO_SETTINGS declared once" || fail "MODIFY_AUDIO_SETTINGS declaration count is not one"
  [[ "$(grep -Ec 'androidx\.media3:media3-exoplayer:' "$GRADLE")" -eq 1 ]] \
    && grep -Fq 'androidx.media3:media3-exoplayer:1.8.0' "$GRADLE" \
    && pass "Media3 ExoPlayer 1.8.0 appears once" || fail "Media3 dependency contract failed"
  for source in CallAudioBridge.java CallAudioUrls.java; do
    cmp -s "$NATIVE/$source" "$GEN_JAVA/$source" \
      && pass "generated $source matches native/" || fail "generated $source missing or stale"
  done
  cmp -s "$NATIVE/MainActivity.java" "$GEN_JAVA/MainActivity.java" \
    && pass "generated MainActivity matches native/" || fail "generated MainActivity missing or stale"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" "$NATIVE/CallAudioUrls.java" "$ROOT/scripts/jvm-tests/P2HCallAudioTest.java"
java -cp "$tmp" xyz.lovestyle.home.canary.P2HCallAudioTest
pass "pure JVM call audio URL tests"

if [[ $FAIL -ne 0 ]]; then
  echo "P2H call audio contract test FAILED" >&2
  exit 1
fi
echo "P2H call audio contract test PASSED"
