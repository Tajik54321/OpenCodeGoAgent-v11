#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/android-stub-compile"
rm -rf "$WORK"
mkdir -p "$WORK/stubs" "$WORK/app"
find "$ROOT/tools/stubs/src" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -d "$WORK/stubs"
jar cf "$WORK/android-stubs.jar" -C "$WORK/stubs" .
find "$ROOT/app/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -Xlint:deprecation \
      -cp "$WORK/android-stubs.jar" -d "$WORK/app"
printf 'ANDROID_STUB_COMPILE=PASS\n'
