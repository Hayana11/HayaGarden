#!/usr/bin/env bash
# Stamp the CI build into the APK so the installed Canary can be told apart.
# `npx cap add android` regenerates app/build.gradle with versionCode 1 /
# versionName "1.0" on every build; this replaces both:
#   versionCode = BUILD_NUMBER (the Actions run number, so later builds install as upgrades)
#   versionName = "canary-<BUILD_NUMBER> (<first 7 of BUILD_SHA>)"
# Usage: BUILD_NUMBER=143 BUILD_SHA=39e4008... bash scripts/apply-build-version.sh [android-root]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_ROOT="${1:-$ROOT/android}"
GRADLE="$ANDROID_ROOT/app/build.gradle"
BUILD_NUMBER="${BUILD_NUMBER:-}"
BUILD_SHA="${BUILD_SHA:-}"

if [[ ! "$BUILD_NUMBER" =~ ^[1-9][0-9]{0,8}$ ]]; then
  echo "BUILD_NUMBER must be a positive integer, got '$BUILD_NUMBER'" >&2
  exit 1
fi
if [[ ! -f "$GRADLE" ]]; then
  echo "missing $GRADLE" >&2
  exit 1
fi
if [[ "$(grep -cE '^[[:space:]]*versionCode[[:space:]]' "$GRADLE")" != 1 \
   || "$(grep -cE '^[[:space:]]*versionName[[:space:]]' "$GRADLE")" != 1 ]]; then
  echo "expected exactly one versionCode and one versionName in $GRADLE" >&2
  exit 1
fi

SHORT_SHA="${BUILD_SHA:0:7}"
NAME="canary-$BUILD_NUMBER"
if [[ -n "$SHORT_SHA" ]]; then
  NAME="$NAME ($SHORT_SHA)"
fi

sed -i -E "s/^([[:space:]]*)versionCode[[:space:]].*/\1versionCode $BUILD_NUMBER/" "$GRADLE"
sed -i -E "s/^([[:space:]]*)versionName[[:space:]].*/\1versionName \"$NAME\"/" "$GRADLE"

grep -qE "^[[:space:]]*versionCode $BUILD_NUMBER\$" "$GRADLE" \
  && grep -qF "versionName \"$NAME\"" "$GRADLE" \
  || { echo "failed to stamp the build version into $GRADLE" >&2; exit 1; }
echo "APK version: $NAME (versionCode $BUILD_NUMBER)"
