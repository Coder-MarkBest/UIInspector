#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${1:-}"
ADB="${ANDROID_HOME:+${ANDROID_HOME}/platform-tools/adb}"
if [[ -z "$ADB" || ! -x "$ADB" ]]; then
  ADB="$(command -v adb || true)"
fi
if [[ -z "$ADB" ]]; then
  echo "adb not found; configure ANDROID_HOME or PATH." >&2
  exit 1
fi

"$ROOT/gradlew" -p "$ROOT" :collector:assembleDebug :sample-target:assembleDebug

if [[ -z "$SERIAL" ]]; then
  SERIAL="$($ADB devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "No authorized Android device found." >&2
  exit 1
fi

"$ADB" -s "$SERIAL" install -r -t "$ROOT/collector/build/outputs/apk/debug/collector-debug.apk"
"$ADB" -s "$SERIAL" install -r -t "$ROOT/sample-target/build/outputs/apk/debug/sample-target-debug.apk"
"$ADB" -s "$SERIAL" shell am start -n com.company.uiinspector.sample/.MainActivity

SNAPSHOT="$($ADB -s "$SERIAL" exec-out content read --uri 'content://com.company.uiinspector.collector/snapshot?scope=global&maxNodes=10000&maxDepth=200')"
if ! grep -q '"schemaVersion":1' <<<"$SNAPSHOT"; then
  echo "Collector did not return protocol v1 JSON." >&2
  exit 1
fi

if grep -q '"accessibility_required"' <<<"$SNAPSHOT"; then
  echo "Collector installed. Enable UI Inspector Collector in Accessibility settings, then capture again."
  "$ADB" -s "$SERIAL" shell am start -a android.settings.ACCESSIBILITY_SETTINGS
else
  echo "Smoke capture succeeded on $SERIAL."
fi
