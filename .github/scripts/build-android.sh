#!/usr/bin/env bash
# Tests and builds the Android app into $1. With the release key in KEYSTORE_BASE64 (and
# KNURA_KEYSTORE_PASSWORD, KNURA_KEY_ALIAS, KNURA_KEY_PASSWORD) it signs a release build with it and
# leaves a signed-with-release-key marker; without it, it falls back to a debug-signed test build.
set -euo pipefail
out="$1"
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  export KNURA_KEYSTORE="$RUNNER_TEMP/knura-release.jks"
  trap 'rm -f "$KNURA_KEYSTORE"' EXIT
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$KNURA_KEYSTORE"
  ./gradlew -p android testDebugUnitTest assembleRelease
  cp android/app/build/outputs/apk/release/app-release.apk "$out"
  touch signed-with-release-key
else
  ./gradlew -p android testDebugUnitTest assembleDebug
  cp android/app/build/outputs/apk/debug/app-debug.apk "$out"
fi
