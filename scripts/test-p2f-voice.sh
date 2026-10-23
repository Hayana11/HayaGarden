#!/usr/bin/env bash
# P2F source/generated-tree and pure JVM contract tests.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
ANDROID_ROOT="${1:-$ROOT/android}"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
GRADLE="$ANDROID_ROOT/app/build.gradle"
GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
GEN_ASSETS="$ANDROID_ROOT/app/src/main/assets"
MODEL="$NATIVE/assets/silero_vad.onnx"
EXPECTED_MODEL_SHA="2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

for source in VoiceBridge.java VoiceCapture.java SileroVad.java VoiceSegmenter.java WavEncoder.java; do
  [[ -f "$NATIVE/$source" ]] && pass "present native/$source" || fail "missing native/$source"
done
[[ -f "$MODEL" ]] && pass "present Silero model" || fail "missing Silero model"
[[ -f "$ROOT/third_party/silero-vad/LICENSE" ]] && pass "Silero MIT license is vendored" || fail "Silero LICENSE missing"
[[ -f "$ROOT/third_party/silero-vad/NOTICE.md" ]] && grep -qF "$EXPECTED_MODEL_SHA" "$ROOT/third_party/silero-vad/NOTICE.md"   && pass "Silero provenance notice includes model SHA256" || fail "Silero provenance notice missing"
if [[ -f "$MODEL" ]]; then
  [[ "$(sha256sum "$MODEL" | awk '{print $1}')" == "$EXPECTED_MODEL_SHA" ]]     && pass "model SHA256 is fixed and verified" || fail "model SHA256 mismatch"
fi

grep -qF 'webView.addJavascriptInterface(voiceBridge, "ElpisVoice")' "$NATIVE/MainActivity.java"   && pass "MainActivity registers ElpisVoice" || fail "ElpisVoice registration missing"
grep -qF 'voiceBridge.release()' "$NATIVE/MainActivity.java"   && pass "native capture releases on pause" || fail "onPause release missing"
grep -qF 'voiceBridge.resumed()' "$NATIVE/MainActivity.java"   && pass "native voice receives resume" || fail "onResume callback missing"
grep -qF 'voiceBridge.close()' "$NATIVE/MainActivity.java"   && pass "native voice closes on destroy" || fail "onDestroy close missing"
grep -qF 'voiceBridge.onPermissionResult(' "$NATIVE/MainActivity.java"   && pass "permission result reaches P2F" || fail "P2F permission forwarding missing"
if grep -Eq 'putInt\(SEQ_KEY,[[:space:]]*0\)|remove\(SEQ_KEY\)|SEQ_PREFS.*\.clear\(|nextSeq[[:space:]]*=' "$NATIVE/VoiceBridge.java"; then
  fail "P2F utterance seq must never be reset (server dedupes by seq within a call)"
else
  grep -qF 'int seq = allocateSeq();' "$NATIVE/VoiceBridge.java"     && grep -qF 'prefs.edit().putInt(SEQ_KEY, seq).commit();' "$NATIVE/VoiceBridge.java"     && pass "P2F utterance seq is persisted and monotonic across mic off/on and restarts"     || fail "P2F persisted utterance seq allocation missing"
fi
grep -qF 'REQUEST_RECORD_AUDIO = 2006' "$NATIVE/VoiceBridge.java"   && pass "P2F permission request code is separate" || fail "wrong P2F request code"
if grep -Eq 'android\\.speech\\.(SpeechRecognizer|RecognitionService)|new[[:space:]]+SpeechRecognizer' "$NATIVE/VoiceBridge.java" "$NATIVE/VoiceCapture.java" "$NATIVE/SileroVad.java"; then
  fail "P2F native voice must not use Android speech APIs"
else
  pass "P2F native voice bypasses Android speech APIs"
fi

if [[ -f "$MANIFEST" ]]; then
  grep -Eq 'applicationId[[:space:]]+["'\'' ]xyz\.lovestyle\.home\.canary' "$GRADLE"     && pass "Canary package remains xyz.lovestyle.home.canary" || fail "Canary package changed"
  [[ "$(grep -Fc 'android.permission.RECORD_AUDIO"' "$MANIFEST")" -eq 1 ]]     && pass "RECORD_AUDIO declared once" || fail "RECORD_AUDIO declaration count is not one"
  [[ -f "$GEN_JAVA/VoiceBridge.java" && -f "$GEN_JAVA/VoiceCapture.java"       && -f "$GEN_JAVA/SileroVad.java" && -f "$GEN_JAVA/VoiceSegmenter.java"       && -f "$GEN_JAVA/WavEncoder.java" ]]     && pass "generated P2F Java sources exist" || fail "generated P2F Java source missing"
  [[ -f "$GEN_ASSETS/silero_vad.onnx" ]]     && pass "generated model asset exists" || fail "generated model asset missing"
  [[ -f "$GRADLE" ]] || fail "generated app Gradle missing"
  if [[ -f "$GRADLE" ]]; then
    [[ "$(grep -Ec 'com\.microsoft\.onnxruntime:onnxruntime-android:' "$GRADLE")" -eq 1 ]]       && grep -Fq 'com.microsoft.onnxruntime:onnxruntime-android:1.20.0' "$GRADLE"       && pass "ORT 1.20.0 appears once" || fail "ORT dependency contract failed"
    grep -Eq 'abiFilters[^\n]*["'\'' ]arm64-v8a' "$GRADLE"       && pass "arm64-v8a ABI filter exists" || fail "arm64-v8a ABI filter missing"
    if grep -Eq 'abiFilters[^\n]*(x86|armeabi-v7a|x86_64)' "$GRADLE"; then
      fail "non-arm64 ABI is present"
    fi
  fi
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp"   "$NATIVE/VoiceSegmenter.java"   "$NATIVE/WavEncoder.java"   "$ROOT/scripts/jvm-tests/P2FVoiceTest.java"
java -cp "$tmp" xyz.lovestyle.home.canary.P2FVoiceTest
pass "pure JVM segmenter/WAV tests"

if [[ "${2:-}" != "" ]]; then
  APK="$2"
  [[ -f "$APK" ]] || { echo "missing APK $APK" >&2; exit 1; }
  apk_listing="$(unzip -l "$APK")"
  grep -Eq 'assets/silero_vad.onnx' <<<"$apk_listing"     && pass "APK contains silero_vad.onnx" || fail "APK does not contain Silero model"
  grep -Eq 'lib/arm64-v8a/' <<<"$apk_listing"     && pass "APK contains arm64-v8a native libraries" || fail "APK has no arm64-v8a native libraries"
  if grep -Eq 'lib/(armeabi-v7a|x86|x86_64)/' <<<"$apk_listing"; then
    fail "APK contains a non-arm64 ABI"
  else
    pass "APK contains no non-arm64 ABI"
  fi
fi

if [[ $FAIL -ne 0 ]]; then
  echo "P2F voice contract test FAILED" >&2
  exit 1
fi
echo "P2F voice contract test PASSED"
