#!/usr/bin/env bash
#
# A scratchpad for reading collateral documents. No Spring, no database, no server.
#
#   ./lab.sh samples/title.pdf
#   ./lab.sh samples/*.pdf samples/*.jpg
#   OCR_BINARY=/opt/homebrew/bin/tesseract ./lab.sh samples/title.pdf
#
# Edit Lab.java -- the patterns and the preprocessing are at the top -- and run again. It
# recompiles each time, which takes about a second.
#
# PDFBox comes from the backend's dependencies, so PDF pages are turned into images exactly
# the way the portal turns them. Resolved once by Maven and cached in .classpath; delete that
# file to refresh it.
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$HERE/../../backend"

if [ ! -s "$HERE/.classpath" ]; then
  echo "Resolving PDFBox from the backend's dependencies (once, this is slow)…" >&2
  (cd "$BACKEND" && ./mvnw -q dependency:build-classpath -Dmdep.outputFile="$HERE/.classpath")
fi

CP="$(cat "$HERE/.classpath")"

mkdir -p "$HERE/out"
javac -nowarn -cp "$CP" -d "$HERE/out" "$HERE/Lab.java" "$HERE/LabServer.java"

# --serve opens the drag-and-drop page instead of printing to the terminal.
if [ "${1:-}" = "--serve" ]; then
  shift
  exec java -cp "$HERE/out:$CP" LabServer "$@"
fi

exec java -cp "$HERE/out:$CP" Lab "$@"
