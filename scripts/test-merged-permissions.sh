#!/usr/bin/env bash
# Permissions in the built APK, after manifest merge: libraries can add
# permissions the source-manifest gates never see. Prints the full list for
# review and enforces the owner decisions that concern merged permissions.
#   BLUETOOTH_CONNECT: merged in by core-telecom; owner-approved 2026-10-24,
#   requested only when a Bluetooth call endpoint is actually wanted.
set -euo pipefail

APK="${1:?usage: test-merged-permissions.sh <apk>}"
[[ -f "$APK" ]] || { echo "missing APK $APK" >&2; exit 1; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT2="$(ls -1 "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
[[ -x "$AAPT2" ]] || { echo "aapt2 not found under $SDK/build-tools" >&2; exit 1; }

permissions="$("$AAPT2" dump permissions "$APK" | sed -n -E "s/.*uses-permission: name='([^']+)'.*/\1/p" | sort -u)"
echo "Merged APK permissions:"
sed 's/^/  /' <<<"$permissions"

FAIL=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAIL=1; }

grep -qx 'android.permission.BLUETOOTH_CONNECT' <<<"$permissions" \
  && pass "BLUETOOTH_CONNECT present (approved, requested on demand only)" \
  || fail "BLUETOOTH_CONNECT expected from core-telecom is missing"
for forbidden in FOREGROUND_SERVICE_MICROPHONE BLUETOOTH_SCAN BLUETOOTH_ADVERTISE CAMERA \
                 ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION \
                 CALL_PHONE READ_PHONE_STATE; do
  if grep -qx "android.permission.$forbidden" <<<"$permissions"; then
    fail "merged APK carries unapproved $forbidden"
  else
    pass "no $forbidden"
  fi
done

if [[ $FAIL -ne 0 ]]; then
  echo "Merged permission check FAILED" >&2
  exit 1
fi
echo "Merged permission check PASSED"
