#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
FONT="$ROOT/liveboot_custom/fonts/LiveBootMono.ttf"
CONFIG="$ROOT/liveboot_custom/config/settings.conf"
RUNNER="$ROOT/liveboot/liveBootAni2/src/main/java/eu/chainfire/liveboot/shell/Runner.java"
LAUNCHER="$ROOT/liveboot_custom/files/liveboot"
RENDERER="$ROOT/liveboot/liveBootAni2/src/main/java/eu/chainfire/liveboot/shell/SpanTextManager.java"

[ -s "$FONT" ] || { echo "FAIL: bundled LiveBootMono.ttf is missing or empty" >&2; exit 1; }
[ -r "$FONT" ] || { echo "FAIL: bundled font is not readable" >&2; exit 1; }
MAGIC=$(od -An -tx1 -N4 "$FONT" | tr -d ' \n')
[ "$MAGIC" = "00010000" ] || { echo "FAIL: bundled font is not a TrueType sfnt (header=$MAGIC)" >&2; exit 1; }
grep -qx 'font=LiveBootMono.ttf' "$CONFIG" || { echo "FAIL: settings.conf does not select bundled test font" >&2; exit 1; }
grep -q 'Typeface.Builder(fontFile)' "$RUNNER" || { echo "FAIL: modern Android font loader missing" >&2; exit 1; }
grep -q 'Typeface.createFromFile(fontFile)' "$RUNNER" || { echo "FAIL: compatibility font loader missing" >&2; exit 1; }
grep -q 'LIVEBOOT_FONT_PATH' "$RUNNER" || { echo "FAIL: Java staged-font environment input missing" >&2; exit 1; }
grep -q 'LIVEBOOT_FONT_FALLBACK_PATH' "$RUNNER" || { echo "FAIL: Java fallback-font environment input missing" >&2; exit 1; }
grep -q 'getDeclaredMethod("loadPreinstalledSystemFontMap")' "$RUNNER" || { echo "FAIL: early app_process Typeface system-map initializer missing" >&2; exit 1; }
grep -q 'Typeface.DEFAULT == null || Typeface.MONOSPACE == null' "$RUNNER" || { echo "FAIL: Typeface initialization result is not validated" >&2; exit 1; }
grep -q 'Typeface system map: ' "$RUNNER" || { echo "FAIL: Typeface initialization status is not included in font diagnostics" >&2; exit 1; }
MAP_INIT_LINE=$(grep -n 'sTypefaceSystemMapStatus = initializeTypefaceSystemMap();' "$RUNNER" | cut -d: -f1)
RUNNER_CONSTRUCT_LINE=$(grep -n 'new Runner().run(args);' "$RUNNER" | cut -d: -f1)
[ -n "$MAP_INIT_LINE" ] && [ -n "$RUNNER_CONSTRUCT_LINE" ] && [ "$MAP_INIT_LINE" -lt "$RUNNER_CONSTRUCT_LINE" ] || { echo "FAIL: Typeface system map must initialize before Runner instance fields read Typeface.MONOSPACE" >&2; exit 1; }
grep -q 'LiveBootMono.ttf also failed' "$RUNNER" || { echo "FAIL: bundled-font fallback diagnostics missing" >&2; exit 1; }
grep -q '/data/local/tmp/liveboot-font' "$LAUNCHER" || { echo "FAIL: launcher staging directory missing" >&2; exit 1; }
grep -q '/system/bin/cp' "$LAUNCHER" || { echo "FAIL: launcher does not copy font files before launch" >&2; exit 1; }
grep -q 'STAGED_FALLBACK_PATH' "$LAUNCHER" || { echo "FAIL: launcher does not stage the bundled fallback font" >&2; exit 1; }
EXPORT_LINE=$(grep -n '^export LIVEBOOT_FONT_PATH LIVEBOOT_FONT_FALLBACK_PATH' "$LAUNCHER" | cut -d: -f1)
LAUNCH_LINE=$(grep -n '^"\$HELPER" "\$APP"' "$LAUNCHER" | cut -d: -f1)
[ -n "$EXPORT_LINE" ] && [ -n "$LAUNCH_LINE" ] && [ "$EXPORT_LINE" -lt "$LAUNCH_LINE" ] || { echo "FAIL: font staging exports must precede the app_process launch" >&2; exit 1; }
grep -q '"line_background".equals(key)' "$RUNNER" || { echo "FAIL: settings parser does not read line_background" >&2; exit 1; }
grep -q 'mConfiguredTypeface, mConfiguredTextSize, mLineBackground' "$RUNNER" || { echo "FAIL: line background setting is not passed into the renderer" >&2; exit 1; }
grep -q 'fonts/LiveBootMono.ttf' "$ROOT/package_module.sh" || { echo "FAIL: package script does not require bundled font" >&2; exit 1; }
[ -s "$ROOT/liveboot_custom/fonts/DEJAVU_FONT_LICENSE.txt" ] || { echo "FAIL: bundled font license missing" >&2; exit 1; }
grep -qx 'line_background=off' "$CONFIG" || { echo "FAIL: line background default is not explicitly disabled" >&2; exit 1; }
grep -q 'canvas.drawRect(left, 0.0f, right' "$RENDERER" || { echo "FAIL: content-sized per-row background drawing missing" >&2; exit 1; }
grep -q 'mLineBackground' "$RENDERER" || { echo "FAIL: line background toggle is not wired into renderer" >&2; exit 1; }
echo "PASS: font staging, early app_process Typeface initialization, Java font loading/fallback, bundled font/license, and content-sized line backgrounds"
