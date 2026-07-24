#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 tools/verify_source.py
bash tools/run_core_smoke.sh
bash tools/run_android_stub_compile.sh
gradle --no-daemon --stacktrace :app:assembleSideloadDebug :app:lintSideloadDebug
APK="app/build/outputs/apk/sideload/debug/app-sideload-debug.apk"
sha256sum "$APK" > "$APK.sha256"
printf 'APK: %s\nSHA: %s\n' "$APK" "$APK.sha256"
