#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
JAR="$ROOT/target/javadb-1.0.0-jar-with-dependencies.jar"

# Build if jar is missing
if [[ ! -f "$JAR" ]]; then
  echo "==> Building JavaDB..."
  mvn -q package -DskipTests -f "$ROOT/pom.xml"
fi

echo "==> Running JavaDB benchmark suite..."
echo ""

java -cp "$JAR" com.javadb.benchmark.BenchmarkSuite

echo ""
echo "==> Done. Results written to benchmark-results.csv (not committed)."
