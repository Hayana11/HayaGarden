#!/usr/bin/env bash
# Phase 2B source and generated-tree contract locks.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native"
MAIN="$NATIVE/MainActivity.java"
NATIVE_BRIDGE="$NATIVE/NativeBridge.java"
BRIDGE="$NATIVE/NotificationBridge.java"
SUPPORT="$NATIVE/NotificationSupport.java"
WORKER="$NATIVE/NotificationPollWorker.java"
ANDROID_ROOT="${1:-$ROOT/android}"
MANIFEST="$ANDROID_ROOT/app/src/main/AndroidManifest.xml"
GRADLE="$ANDROID_ROOT/app/build.gradle"
GEN_JAVA="$ANDROID_ROOT/app/src/main/java/xyz/lovestyle/home/canary"
FAIL=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }
require_file() { [[ -f "$1" ]] && pass "present $1" || fail "missing $1"; }

for file in "$MAIN" "$NATIVE_BRIDGE" "$BRIDGE" "$SUPPORT" "$WORKER"; do
  require_file "$file"
done

methods_after_js() {
  awk '
    /@JavascriptInterface/ { pending=1; next }
    pending && /@SuppressWarnings/ { next }
    pending && /public[[:space:]]+(String|boolean|void|int|long|double|float)[[:space:]]+([A-Za-z0-9_]+)[[:space:]]*\(/ {
      print $0; pending=0; next
    }
    pending && /public/ { pending=0 }
  ' "$1" | sed -E 's/.*public[[:space:]]+[A-Za-z0-9_<>]+[[:space:]]+([A-Za-z0-9_]+)[[:space:]]*\(.*/\1/'
}

mapfile -t P2A_METHODS < <(methods_after_js "$NATIVE_BRIDGE")
EXPECTED_P2A=(getBattery getScreenTime hasUsageAccess openUsageAccessSettings isIgnoringBatteryOptimizations requestIgnoreBatteryOptimizations)
[[ ${#P2A_METHODS[@]} -eq 6 ]] || fail "NativeBridge must expose exactly six methods"
for method in "${EXPECTED_P2A[@]}"; do
  printf '%s\n' "${P2A_METHODS[@]}" | grep -qx "$method" && pass "P2A keeps $method" || fail "P2A missing $method"
done

mapfile -t P2B_METHODS < <(methods_after_js "$BRIDGE")
EXPECTED_P2B=(hasNotificationPermission requestNotificationPermission showTestNotification)
[[ ${#P2B_METHODS[@]} -eq 3 ]] || fail "NotificationBridge must expose exactly three methods"
for method in "${EXPECTED_P2B[@]}"; do
  printf '%s\n' "${P2B_METHODS[@]}" | grep -qx "$method" && pass "P2B exposes $method" || fail "P2B missing $method"
done
grep -q '"ElpisNotifications"' "$MAIN" && pass "MainActivity injects ElpisNotifications" || fail "missing notification bridge injection"

ON_CREATE="$(sed -n '/protected void onCreate/,/protected void onNewIntent/p' "$MAIN")"
if printf '%s\n' "$ON_CREATE" | grep -Eq 'requestPermissions\(|requestNotificationPermission\('; then
  fail "onCreate must not request notification permission"
else
  pass "no automatic notification permission prompt"
fi

line_of() { grep -n "$2" "$1" | head -n1 | cut -d: -f1; }
PRE=$(line_of "$WORKER" 'canPostNotifications')
URL=$(line_of "$WORKER" 'new URL')
OPEN=$(line_of "$WORKER" 'openConnection')
INPUT=$(line_of "$WORKER" 'getInputStream')
if [[ -n "$PRE" && -n "$URL" && -n "$OPEN" && -n "$INPUT" && "$PRE" -lt "$URL" && "$PRE" -lt "$OPEN" && "$PRE" -lt "$INPUT" ]]; then
  pass "worker exits before every HTTP construction/read when notifications unavailable"
else
  fail "worker preflight must precede URL, connection, and input stream"
fi
grep -q 'setRequestMethod("GET")' "$WORKER" && pass "worker uses GET" || fail "worker must use GET"
if grep -Eq 'setRequestMethod\("(POST|PUT|PATCH|DELETE)"\)' "$WORKER"; then
  fail "worker has a forbidden HTTP method"
fi
if grep -qiE 'command|ScreenCapture|screenshot|Pocket|GPS|location|AppTracker' "$WORKER"; then
  fail "worker contains forbidden remote-action capability"
else
  pass "worker is display-only"
fi
if grep -R -E 'ForegroundService|startForegroundService|startService\(|BootReceiver' "$NATIVE"/*.java; then
  fail "custom native source contains forbidden service or receiver"
else
  pass "no custom foreground service or receiver"
fi

for token in 'CHANNEL_ID = "fyodor_msg"' 'CHANNEL_NAME = "费奥多尔的消息"' 'WORK_NAME = "poll_fyodor"' 'CHAT_URL = "https://love-style.xyz/dash/chat"' 'PeriodicWorkRequest' '15, TimeUnit.MINUTES' 'NetworkType.CONNECTED' 'ExistingPeriodicWorkPolicy.KEEP'; do
  grep -qF "$token" "$SUPPORT" && pass "notification contract has $token" || fail "missing $token"
done
grep -qF 'EXTRA_OPEN_CHAT' "$SUPPORT" && grep -qF 'handleNotificationIntent' "$MAIN" \
  && grep -qF 'onNewIntent' "$MAIN" && grep -qF 'webView.loadUrl(NotificationSupport.CHAT_URL)' "$MAIN" \
  && pass "notification tap is gated to Chat" || fail "tap-to-Chat contract missing"
if grep -Eq 'onResume|reload\(|clearCache|clearHistory' "$MAIN"; then
  fail "lifecycle regression in MainActivity"
else
  pass "P1B lifecycle baseline retained"
fi

if [[ -f "$MANIFEST" ]]; then
  for need in INTERNET PACKAGE_USAGE_STATS REQUEST_IGNORE_BATTERY_OPTIMIZATIONS POST_NOTIFICATIONS; do
    grep -q "android.permission.$need" "$MANIFEST" && pass "manifest has $need" || fail "manifest missing $need"
  done
  if grep -Eq 'android.permission.(FOREGROUND_SERVICE|ACCESS_.*LOCATION|RECEIVE_BOOT_COMPLETED|CAMERA|RECORD_AUDIO)' "$MANIFEST"; then
    fail "manifest contains forbidden permission"
  else
    pass "manifest has no forbidden permission"
  fi
  for name in MainActivity NativeBridge NotificationBridge NotificationSupport NotificationPollWorker; do
    [[ -f "$GEN_JAVA/$name.java" ]] && pass "generated $name.java exists" || fail "generated $name.java missing"
  done
fi
if [[ -f "$GRADLE" ]]; then
  [[ "$(grep -Fc 'androidx.work:work-runtime:2.9.0' "$GRADLE")" -eq 1 ]] \
    && pass "one WorkManager dependency" || fail "WorkManager dependency must occur exactly once"
  if grep -qiE 'okhttp|firebase|play-services|local-notifications' "$GRADLE"; then
    fail "forbidden notification dependency present"
  fi
fi

[[ "$FAIL" -eq 0 ]] || exit 1
echo "P2B contract test PASSED"
