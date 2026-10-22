#!/usr/bin/env bash
# Phase 2D: held-open push long poll in a remoteMessaging foreground service,
# restarted after boot. Composes on top of the full P2C.1 + Health chain.
# NOT wired into CI yet: P2B's contract still forbids foreground services and
# boot receivers until the owner lifts that lock for this phase.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="$ROOT/native-p2d"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"

for required in "$NATIVE/PushService.java" "$NATIVE/PushBootReceiver.java" "$MANIFEST"; do
  if [[ ! -f "$required" ]]; then
    echo "missing $required" >&2
    exit 1
  fi
done

mkdir -p "$DEST_DIR"
cp "$NATIVE/PushService.java" "$DEST_DIR/PushService.java"
cp "$NATIVE/PushBootReceiver.java" "$DEST_DIR/PushBootReceiver.java"

ensure_permission() {
  if ! grep -Fq "android.permission.$1\"" "$MANIFEST"; then
    sed -i "/<application/i\    <uses-permission android:name=\"android.permission.$1\" />" "$MANIFEST"
  fi
}
ensure_permission FOREGROUND_SERVICE
ensure_permission FOREGROUND_SERVICE_REMOTE_MESSAGING
ensure_permission RECEIVE_BOOT_COMPLETED

ensure_application_block() {
  local marker="$1"
  local block="$2"
  if grep -Fq "$marker" "$MANIFEST"; then
    return
  fi
  local tmp
  tmp="$(mktemp)"
  if ! awk -v block="$block" '
    !inserted && /<\/application>/ { print block; inserted = 1 }
    { print }
    END { if (!inserted) exit 1 }
  ' "$MANIFEST" > "$tmp"; then
    rm -f "$tmp"
    echo "missing closing application block in $MANIFEST" >&2
    exit 1
  fi
  mv "$tmp" "$MANIFEST"
}

ensure_application_block 'android:name=".PushService"' "$(cat <<'XML'
    <service
        android:name=".PushService"
        android:exported="false"
        android:foregroundServiceType="remoteMessaging" />
XML
)"
ensure_application_block 'android:name=".PushBootReceiver"' "$(cat <<'XML'
    <receiver
        android:name=".PushBootReceiver"
        android:exported="false">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        </intent-filter>
    </receiver>
XML
)"

echo "Phase 2D push long poll applied"
