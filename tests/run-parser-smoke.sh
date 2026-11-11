#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

javac -d "$TMP/classes" \
    "$ROOT/tests/parser-smoke/android/graphics/Color.java" \
    "$ROOT/liveboot/liveBootAni2/src/main/java/eu/chainfire/liveboot/shell/TextSpan.java" \
    "$ROOT/liveboot/liveBootAni2/src/main/java/eu/chainfire/liveboot/shell/ScriptParser.java" \
    "$ROOT/tests/parser-smoke/eu/chainfire/liveboot/shell/ParserSmokeTest.java"

java -cp "$TMP/classes" eu.chainfire.liveboot.shell.ParserSmokeTest
