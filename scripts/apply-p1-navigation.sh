#!/usr/bin/env bash
# Phase 1B: install minimal back-button MainActivity + stable debug signing
# into a freshly generated Capacitor Android project.
# Does NOT restore Phase-0-forbidden services, bridges, patches, or permissions.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/MainActivity.java"
DEST_DIR="$ROOT/android/app/src/main/java/xyz/lovestyle/home/canary"
DEST="$DEST_DIR/MainActivity.java"
GRADLE="$ROOT/android/app/build.gradle"
P12_B64="$ROOT/scripts/elpis-debug.p12.b64"
P12_DEST="$ROOT/android/app/elpis-debug.p12"
SIGN_MARKER="elpisDebug"

if [[ ! -f "$SRC" ]]; then
  echo "missing $SRC" >&2
  exit 1
fi
if [[ ! -d "$ROOT/android/app/src/main" ]]; then
  echo "android project missing; run npx cap add android first" >&2
  exit 1
fi

mkdir -p "$DEST_DIR"
cp "$SRC" "$DEST"
echo "installed Phase 1B MainActivity → $DEST"

if [[ ! -f "$P12_B64" ]]; then
  echo "missing $P12_B64 (stable debug keystore)" >&2
  exit 1
fi
base64 -d "$P12_B64" > "$P12_DEST"
echo "decoded stable debug keystore → $P12_DEST"

if [[ ! -f "$GRADLE" ]]; then
  echo "missing $GRADLE" >&2
  exit 1
fi
if ! grep -q "$SIGN_MARKER" "$GRADLE"; then
  cat >> "$GRADLE" <<'GRADLE'

// Stable debug signing — same cert across CI builds so APKs are over-installable.
android {
    signingConfigs {
        elpisDebug {
            storeFile file('elpis-debug.p12')
            storeType 'PKCS12'
            storePassword 'elpisdebug'
            keyAlias 'elpisdebug'
            keyPassword 'elpisdebug'
        }
    }
    buildTypes {
        debug {
            signingConfig signingConfigs.elpisDebug
        }
    }
}
GRADLE
  echo "appended stable debug signingConfig to $GRADLE"
else
  echo "stable debug signingConfig already present in $GRADLE"
fi
