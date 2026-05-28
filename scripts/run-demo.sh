#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
JAR="$ROOT/target/javadb-1.0.0-jar-with-dependencies.jar"
DATA_DIR="/tmp/javadb-demo-$$"

# Build if jar is missing
if [[ ! -f "$JAR" ]]; then
  echo "==> Building JavaDB..."
  mvn -q package -DskipTests -f "$ROOT/pom.xml"
fi

echo "==> Running demo (data dir: $DATA_DIR)"
echo ""

java -jar "$JAR" "$DATA_DIR" < "$SCRIPT_DIR/demo.sql"

echo ""
echo "==> Cleaning up $DATA_DIR"
rm -rf "$DATA_DIR"
