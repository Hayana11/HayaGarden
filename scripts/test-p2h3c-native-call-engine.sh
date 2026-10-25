#!/usr/bin/env bash
# P2H-3C native call engine: source and generated-tree contract.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
ANDROID_ROOT="${1:-}"
ENGINE="$NATIVE/NativeCallEngine.kt"
API="$NATIVE/NativeCallApi.kt"
PROTOCOL="$NATIVE/NativeCallProtocol.java"
PROBE="$NATIVE/NativeCallProbeActivity.kt"
SOURCES=(NativeCallEngine.kt NativeCallApi.kt NativeCallProtocol.java CallRunGate.java CallStartIds.java CallLanes.java NativeCallProbeActivity.kt)
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }
has() { grep -qF -- "$1" "$2"; }
code() { grep -Ev '^[[:space:]]*(\*|/\*|//)' "$@"; }

for source in "${SOURCES[@]}"; do
  [[ -f "$NATIVE/$source" ]] && pass "present native/$source" || fail "missing native/$source"
done

# Same protocol as the WebView runtime, on the fixed origin, URLs built in one place.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" "$PROTOCOL" "$NATIVE/CallAudioUrls.java" "$ROOT/scripts/jvm-tests/P2H3CNativeCallTest.java" \
  && java -cp "$tmp" xyz.lovestyle.home.canary.P2H3CNativeCallTest \
  && pass "protocol JVM tests (URLs, utterance chain, line queue, SSE)" || fail "protocol JVM tests"
if code "$API" "$ENGINE" | grep -Eq '"https?://|love-style'; then
  fail "the engine builds a URL outside NativeCallProtocol"
else
  pass "every URL comes from NativeCallProtocol"
fi
has 'CookieManager.getInstance().getCookie(CallAudioUrls.ORIGIN)' "$API" \
  && pass "owner session is the WebView cookie, attached natively" || fail "cookie handling changed"

# 3C boundary: not wired to CallScreen, the WebView runtime is untouched.
if code "$ENGINE" "$API" "$PROBE" | grep -Eq 'JavascriptInterface|evaluateJavascript|WebView|VoiceBridge|CallAudioBridge'; then
  fail "the engine reaches into the WebView call loop"
else
  pass "the engine stays out of the WebView call loop"
fi
if grep -Eq 'NativeCall' "$NATIVE/MainActivity.java" "$NATIVE/VoiceBridge.java" "$NATIVE/CallAudioBridge.java"; then
  fail "the WebView side references the native engine (that is 3E)"
else
  pass "CallScreen's shell does not reference the native engine"
fi

# Telecom owns the lifecycle and the endpoint; the engine never routes through AudioManager.
has 'manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)' "$ENGINE" && has 'manager.addCall(' "$ENGINE" \
  && pass "Core-Telecom call" || fail "Core-Telecom registration/addCall missing"
has 'requestEndpointChange(target)' "$ENGINE" && has 'availableEndpoints.collect' "$ENGINE" && has 'currentCallEndpoint.collect' "$ENGINE" \
  && pass "routing is Telecom endpoints" || fail "endpoint routing missing"
if code "$ENGINE" | grep -Eq 'setMode\(|\.mode *=|setCommunicationDevice|clearCommunicationDevice|setSpeakerphoneOn|requestAudioFocus'; then
  fail "the engine routes or takes focus through AudioManager"
else
  pass "no AudioManager routing, mode or focus in the engine"
fi
has 'ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL' "$ENGINE" && pass "phoneCall FGS type" || fail "phoneCall FGS type missing"
if grep -Fq 'FOREGROUND_SERVICE_TYPE_MICROPHONE' "$ENGINE"; then fail "microphone FGS type"; else pass "phoneCall only"; fi
has 'if (answered) DisconnectCause.LOCAL else DisconnectCause.REJECTED' "$ENGINE" && has 'DisconnectCause.REMOTE' "$ENGINE" \
  && pass "REJECTED unanswered, LOCAL answered, REMOTE when the server ends it" || fail "disconnect causes"
has 'api.ring(language)' "$ENGINE" && python3 - "$ENGINE" <<'PY' && pass "the server rings before Telecom carries the call" || fail "addCall before the server ring"
import sys
src = open(sys.argv[1]).read()
begin = src[src.index('private fun begin('):src.index('private fun addCall(')]
assert begin.index('api.ring(language)') < begin.index('addCall(r, id)')
PY

# Lifecycle latch (review P1, 2026-10-25): one run at a time, each step once, and an
# old call's commands and callbacks never reach a new one.
javac -d "$tmp" "$NATIVE/CallRunGate.java" "$ROOT/scripts/jvm-tests/P2H3CCallRunGateTest.java" \
  && java -cp "$tmp" xyz.lovestyle.home.canary.P2H3CCallRunGateTest \
  && pass "gate regressions: double dial, double answer, double hangup, redial right after the end" \
  || fail "call run gate JVM tests"
python3 - "$ENGINE" <<'PY' && pass "the engine goes through the gate and keeps commands per call" || fail "engine lifecycle wiring"
import re, sys
src = open(sys.argv[1]).read()
def body(signature):
    part = src[src.index(signature):]
    return part[:part.index('\n    }\n')]
service = src[src.index('class NativeCallEngine : Service() {'):src.index('private inner class Run(')]
assert 'Channel<' not in service, 'a service-wide command channel'
run = body('private inner class Run(')
assert 'val commands = Channel<String>(Channel.UNLIMITED)' in run
assert 'val gen = gate.tryStart()' in body('private fun begin(') and 'if (gen == 0)' in body('private fun begin(')
assert 'if (gate.isCurrent(gen)) {' in body('private fun begin(')
assert 'main.post { finish(r, "ring failed") }' in src
handle = body('private suspend fun CallControlScope.handle(')
assert handle.count('answerOnServer(r)') == handle.count('gate.tryAnswer(r.gen)) answerOnServer(r)') >= 2, 'an answer path skips the gate'
d = body('private suspend fun CallControlScope.disconnectAs(')
assert d.index('if (!gate.tryEnd(r.gen))') < d.index('stopMedia(')
a = body('private fun answerOnServer(')
assert a.count('if (gate.isCurrent(r.gen)) r.commands.trySend(') == 2
f = body('private fun finish(r: Run, reason: String)')
assert f.split('{', 1)[1].lstrip().startswith('if (!gate.finish(r.gen)) return') and 'r.commands.close()' in f
cmd = body('private fun command(')
assert '.setData(Uri.parse(NativeCallProtocol.actionUri(r.gen, action)))' in cmd and 'putExtra(EXTRA_GENERATION, r.gen)' in cmd
start = body('override fun onStartCommand(')
assert 'NativeCallProtocol.actionGeneration(' in start and 'intent.dataString' in start
assert src.count('PendingIntent.getService(') == 1, 'another service PendingIntent without the generation identity'
disp = body('private fun dispatch(')
assert 'val r = ownOrStop(startId)' in disp and 'gen != 0 && gen != r.gen' in disp
assert 'gate.isCurrent(it.gen)' in body('private fun ownOrStop(')
assert re.search(r'\bcommands\.trySend', src.replace('r.commands.trySend', '').replace('owner.commands.trySend', '')) is None
PY

# Start-id ownership (review P1, 2026-10-25): an ended call stops the service only with
# the start requests it owns, never a service-wide latest id, so a redial survives.
javac -d "$tmp" "$NATIVE/CallStartIds.java" "$ROOT/scripts/jvm-tests/P2H3CCallStartIdsTest.java" \
  && java -cp "$tmp" xyz.lovestyle.home.canary.P2H3CCallStartIdsTest \
  && pass "start ids: redial survives the old finish, no idle service after a call" || fail "call start ids JVM tests"
python3 - "$ENGINE" <<'PY' && pass "stopSelf only with the call's own start ids" || fail "stopSelf ownership"
import re, sys
src = open(sys.argv[1]).read()
code = '\n'.join(l for l in src.splitlines() if not l.strip().startswith(('*', '//', '/*')))
assert not re.search(r'(lastStartId|latestStartId)', code), 'a service-wide start id'
stops = [code[m.end():m.end() + 20] for m in re.finditer(r'stopSelf\(', code)]
assert len(stops) == 2 and sum(x.startswith('startId)') for x in stops) == 1 \
    and sum(x.startswith('r.starts.stopId())') for x in stops) == 1, stops
assert 'val starts = CallStartIds(startId)' in src
own = src[src.index('private fun ownOrStop('):]
own = own[:own.index('\n    }\n')]
assert 'if (r != null) r.starts.own(startId) else stopSelf(startId)' in own
assert 'ownOrStop(startId)' in src[src.index('private fun begin('):src.index('val r = Run(gen, asIncoming, startId, legacy, pinTrack)')]
assert 'val r = ownOrStop(startId)' in src[src.index('private fun dispatch('):]
PY

# r3 lesson 1: watchers end with the call.
has 'r.watchers = launch {' "$ENGINE" && has 'stopWatchers(r, "disconnect succeeded")' "$ENGINE" \
  && has 'stopWatchers(r, "telecom onDisconnect")' "$ENGINE" \
  && pass "Telecom state collectors end with the call" || fail "watchers outlive the call"

# r3 lesson 2: local media cleanup never waits on the Telecom scope.
python3 - "$ENGINE" <<'PY' && pass "media stops before disconnect() and on onDisconnect, without the scope" || fail "media cleanup waits on Telecom"
import sys
src = open(sys.argv[1]).read()
d = src[src.index('private suspend fun CallControlScope.disconnectAs('):]
d = d[:d.index('\n    }\n')]
assert d.index('stopMedia(') < d.index('disconnect(DisconnectCause(cause))')
assert 'stopMedia(r, "telecom onDisconnect")' in src
assert 'stopMedia(r, "teardown watchdog")' in src
stop = src[src.index('        fun stop(reason: String) {'):]
stop = stop[:stop.index('\n        }\n')]
for blocking in ('.join(', 'runBlocking', 'await', '.disconnect()\n'):
    assert blocking not in stop.replace('lanes.cleanup { open?.disconnect() }', ''), blocking
PY
has 'TEARDOWN_TIMEOUT_MS' "$ENGINE" && has 'r.callJob?.cancel()' "$ENGINE" && has 'finish(r, "teardown watchdog")' "$ENGINE" \
  && pass "bounded teardown watchdog" || fail "no bounded teardown"

# r3 lesson 3: the main thread never joins the recording thread.
if code "$ENGINE" | grep -Fq '.join('; then fail "the engine joins a thread"; else pass "no thread join in the engine"; fi
python3 - "$ENGINE" <<'PY' && pass "the recorder is closed only on the cleanup executor" || fail "recorder closed outside the cleanup executor"
import re, sys
src = open(sys.argv[1]).read()
closes = [m.start() for m in re.finditer(r'current\.close\(\)', src)]
assert closes, 'no capture close'
for at in closes:
    opened = src.rfind('lanes.cleanup {', 0, at)
    assert opened >= 0 and '}' not in src[opened + len('lanes.cleanup {'):at], 'close() outside the cleanup lane'
assert 'capture?.stop()' not in src and '.stop() // capture' not in src
PY
if code "$ENGINE" | grep -Eq 'Process\.killProcess|exitProcess|System\.exit'; then fail "process kill"; else pass "no process kill"; fi

# Barge-in and the utterance chain, as the page does it.
has 'val prev = chain.start(seq)' "$ENGINE" && has 'api.interrupt(id, seq, prev)' "$ENGINE" \
  && has 'api.hear(id, seq, prev, wav)' "$ENGINE" && has 'api.discard(id, seq, prev)' "$ENGINE" \
  && pass "interrupt at speech_start, hear/discard with the prev fixed then" || fail "utterance protocol"
has 'main.post { stopSpeaking(); stopReplyWatch() }' "$ENGINE" && pass "speech_start silences him" || fail "no barge-in"

# P2H-3D lanes (Reno16 2026-10-25: one /interrupt held the shared control thread 20 s,
# and hear 4 reached the server before interrupt 4).
javac -d "$tmp" "$NATIVE/CallLanes.java" "$ROOT/scripts/jvm-tests/P2H3DCallLanesTest.java" \
  && java -cp "$tmp" xyz.lovestyle.home.canary.P2H3DCallLanesTest \
  && pass "lanes: a stuck interrupt never delays answer/hangup; interrupt N before hear N, bounded" \
  || fail "call lanes JVM tests"
python3 - "$ENGINE" "$API" <<'PY' && pass "each request rides its own lane; barge-in has a short timeout" || fail "request lanes"
import re, sys
src = open(sys.argv[1]).read()
api = open(sys.argv[2]).read()
assert 'Executors.' not in src and 'ExecutorService' not in src, 'an executor outside CallLanes'
def lane_of(call):
    at = src.index(call)
    return max(('lanes.lifecycle {', 'lanes.interrupt(', 'lanes.utterance(', 'lanes.cleanup {'),
               key=lambda lane: src.rfind(lane, 0, at))
for call in ('api.ring(language)', 'api.answer(id).also', 'api.answer(id)\n', 'api.hangup(id)'):
    assert lane_of(call) == 'lanes.lifecycle {', call
assert lane_of('api.interrupt(id, seq, prev)') == 'lanes.interrupt(', 'interrupt'
assert lane_of('api.hear(id, seq, prev, wav)') == 'lanes.utterance(', 'hear'
assert lane_of('api.discard(id, seq, prev)') == 'lanes.utterance(', 'discard'
interrupt = api[api.index('fun interrupt('):]
interrupt = interrupt[:interrupt.index('\n    }\n')]
assert 'readTimeout = INTERRUPT_TIMEOUT_MS' in interrupt and 'connectTimeout = INTERRUPT_TIMEOUT_MS' in interrupt
assert int(re.search(r'INTERRUPT_TIMEOUT_MS = ([0-9_]+)', api).group(1).replace('_', '')) <= 5000
order = int(re.search(r'ORDER_WAIT_MS = ([0-9_]+)L', src).group(1).replace('_', ''))
assert 5000 <= order <= 10000, 'hear must outwait the interrupt timeout, but only just'
PY

# Reply wait: parity with the WebView's P2F path (capture never closes, nothing cut off);
# a reply slower than the WebView's 45 s is logged as a diagnostic, never acted on.
python3 - "$ENGINE" <<'PY' && pass "45 s reply diagnostic is log-only" || fail "reply diagnostic"
import re, sys
src = open(sys.argv[1]).read()
assert re.search(r'REPLY_DIAGNOSTIC_MS = 45_000L', src)
late = src[src.index('private val replyLate = Runnable {'):]
late = late[:late.index('\n        }\n')]
for action in ('commands', 'hangup', 'interrupt', 'closeCapture', 'stop(', 'disconnect'):
    assert action not in late, action
PY

# Route telemetry (read only) and the Core-Telecom modern/legacy A/B (probe only).
python3 - "$ENGINE" "$PROBE" <<'PY' && pass "route telemetry is read only; legacy Telecom path only on the probe's A/B switch" || fail "route telemetry / A/B"
import sys
src = open(sys.argv[1]).read()
probe = open(sys.argv[2]).read()
report = src[src.index('private fun routeReport('):]
report = report[:report.index('\n    }\n')]
for read in ('audio.mode', 'audio.communicationDevice', 'getAudioDevicesForAttributes(VOICE_ATTRIBUTES)',
             'activePlaybackConfigurations', 'volumeControlStream', 'config.audioDeviceInfo'):
    assert read in report, read
for moment in ('routeReport(r, "live")', 'routeReport(r, "endpoint")', 'routeReport(owner, "before player")',
               'routeReport(owner, "playing $seq")'):
    assert moment in src, moment
assert 'manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE, Build.VERSION.SDK_INT)' in src
assert src.index('if (r.legacy) {') < src.index('CAPABILITY_BASELINE, Build.VERSION.SDK_INT')
assert 'legacyTelecom = legacyView.isChecked' in probe
# r2 experiment: the track follows Telecom's endpoint, only on the probe switch, only on our own track
pin = src[src.index('fun followEndpoint() {'):]
pin = pin[:pin.index('\n        }\n')]
assert pin.split('{', 1)[1].lstrip().startswith('if (!owner.pinTrack || !live) return')
assert 'current.setPreferredAudioDevice(device)' in pin
for route_write in ('setCommunicationDevice', 'setSpeakerphoneOn', 'setMode', 'requestEndpointChange'):
    assert route_write not in pin, route_write
assert 'pinTrack = pinView.isChecked' in probe
PY
has '.setAudioAttributes(attributes, /* handleAudioFocus= */ false)' "$ENGINE" && has 'C.USAGE_VOICE_COMMUNICATION' "$ENGINE" \
  && pass "his voice on the communication path, no focus handling" || fail "player attributes"

# Permissions: asked only from the button that needs them.
python3 - "$PROBE" <<'PY' && pass "mic and Nearby devices requested on demand only, never in onCreate" || fail "permission requests"
import sys
src = open(sys.argv[1]).read()
create = src[src.index('override fun onCreate('):]
create = create[:create.index('\n    }\n')]
assert 'requestPermissions' not in create
action = src[src.index('private fun onAction('):]
action = action[:action.index('\n    }\n')]
assert action.count('requestPermissions(') == 2 and src.count('requestPermissions(') == 2
assert 'Manifest.permission.BLUETOOTH_CONNECT' in action and 'needsNearbyDevices()' in action
assert 'Manifest.permission.RECORD_AUDIO' in action and 'starts &&' in action
PY
has 'log("Bluetooth skipped: Nearby devices not granted; the call stays on earpiece/speaker")' "$ENGINE" \
  && has 'staying on the current earpiece/speaker endpoint' "$PROBE" \
  && pass "Bluetooth denial degrades without ending the call" || fail "Bluetooth denial path"
if code "$ENGINE" | grep -Fq 'requestPermissions'; then fail "the service requests permissions"; else pass "the service never requests permissions"; fi
has 'const val TAG = "ElpisCallEngine"' "$ENGINE" && pass "logcat tag ElpisCallEngine" || fail "log tag"

if [[ -n "$ANDROID_ROOT" ]]; then
  MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
  GRADLE="$ANDROID_ROOT/app/build.gradle"
  GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
  for permission in MANAGE_OWN_CALLS FOREGROUND_SERVICE_PHONE_CALL USE_FULL_SCREEN_INTENT RECORD_AUDIO; do
    [[ "$(grep -Fc "android.permission.$permission\"" "$MANIFEST")" -eq 1 ]] \
      && pass "$permission declared once" || fail "$permission declaration count is not one"
  done
  if grep -Eq 'android.permission.(CALL_PHONE|READ_PHONE_STATE|FOREGROUND_SERVICE_MICROPHONE)"' "$MANIFEST"; then
    fail "SIM telephony or microphone FGS permission declared"
  else
    pass "no SIM telephony or microphone FGS permission"
  fi
  grep -Fq 'android:name=".NativeCallEngine"' "$MANIFEST" && grep -Fq 'android:foregroundServiceType="phoneCall"' "$MANIFEST" \
    && pass "engine service declared as phoneCall" || fail "engine service declaration"
  [[ "$(grep -Ec 'androidx\.core:core-telecom:' "$GRADLE")" -eq 1 ]] && grep -Fq 'androidx.core:core-telecom:1.1.0-rc01' "$GRADLE" \
    && pass "core-telecom 1.1.0-rc01 appears once" || fail "core-telecom dependency contract"
  [[ "$(grep -Ec '^[[:space:]]*compileSdkMinor[[:space:]]*=[[:space:]]*1' "$GRADLE")" -eq 1 ]] \
    && pass "compileSdkMinor = 1" || fail "compileSdkMinor missing or duplicated"
  for source in "${SOURCES[@]}"; do
    cmp -s "$NATIVE/$source" "$GEN_JAVA/$source" && pass "generated $source matches native/" || fail "generated $source missing or stale"
  done
fi

if [[ $FAIL -ne 0 ]]; then
  echo "P2H-3C native call engine contract test FAILED" >&2
  exit 1
fi
echo "P2H-3C native call engine contract test PASSED"
