#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/core-smoke"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
  "$ROOT/app/src/main/java/com/qandil/opencodego/server/LocalWebServer.java" \
  "$ROOT/app/src/main/java/com/qandil/opencodego/database/SqlSafety.java" \
  "$ROOT/app/src/main/java/com/qandil/opencodego/util/ZipUtil.java" \
  "$ROOT/tools/CoreSmokeTest.java"
java -cp "$OUT" CoreSmokeTest
