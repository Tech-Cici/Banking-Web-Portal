#!/usr/bin/env bash
#
# Reads a document the way the portal does, and says what it would decide.
#
# It compiles one file against the backend's own classes, so there is no second copy of the
# patterns and no server, database or login involved. First run resolves the backend's
# classpath and caches it; delete .classpath to refresh after changing dependencies.
#
#   ./probe.sh ~/Desktop/title.jpg              try it as every kind
#   ./probe.sh LAND ~/Desktop/title.jpg         try it as one kind
#   OCR_BINARY=/opt/homebrew/bin/tesseract ./probe.sh ...
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$HERE/../../backend"

if [ ! -d "$BACKEND/target/classes" ]; then
  echo "The backend has not been compiled. Run this first:" >&2
  echo "  (cd '$BACKEND' && ./mvnw -q -DskipTests compile)" >&2
  exit 1
fi

# The backend's dependencies, resolved once. Maven is slow; this is why it is cached.
if [ ! -s "$HERE/.classpath" ]; then
  echo "Resolving the backend classpath (once)…" >&2
  (cd "$BACKEND" && ./mvnw -q dependency:build-classpath -Dmdep.outputFile="$HERE/.classpath")
fi

CP="$BACKEND/target/classes:$(cat "$HERE/.classpath")"

mkdir -p "$HERE/out"
javac -nowarn -cp "$CP" -d "$HERE/out" "$HERE/src/rw/bank/ibanking/collateral/OcrProbe.java"

exec java -cp "$HERE/out:$CP" rw.bank.ibanking.collateral.OcrProbe "$@"
