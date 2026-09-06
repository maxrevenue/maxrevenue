#!/usr/bin/env bash
# Build font manager JAR and launch client with -javaagent
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/build/fontmanager-windows.jar"
CLIENT_JAR="${CLIENT_JAR:-$ROOT/game.jar}"

echo "Building..."
"$ROOT/build.bat" || bash -c "cd '$ROOT' && ./gradlew build 2>/dev/null" || true

if [[ ! -f "$JAR" ]]; then
  echo "Build output not found: $JAR" >&2
  exit 1
fi

if [[ ! -f "$CLIENT_JAR" ]]; then
  echo "Client JAR not found: $CLIENT_JAR (set CLIENT_JAR=...)" >&2
  exit 1
fi

TMP_JAR="${TMPDIR:-/tmp}/fontmanager-windows.jar"
cp -f "$JAR" "$TMP_JAR"

exec java \
  -Dsun.java2d.dpiaware=true \
  -Xmx4g -Xms1g \
  -javaagent:"$TMP_JAR" \
  -jar "$CLIENT_JAR"
