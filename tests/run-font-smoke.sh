#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
FONT="$ROOT/liveboot_custom/fonts/LiveBootMono.ttf"
CONFIG="$ROOT/liveboot_custom/config/settings.conf"
RUNNER="$ROOT/liveboot/liveBootAni2/src/main/java/eu/chainfire/liveboot/shell/Runner.java"

[ -s "$FONT" ] || { echo "FAIL: bundled LiveBootMono.ttf is missing or empty" >&2; exit 1; }
[ -r "$FONT" ] || { echo "FAIL: bundled font is not readable" >&2; exit 1; }
MAGIC=$(od -An -tx1 -N4 "$FONT" | tr -d ' \n')
[ "$MAGIC" = "00010000" ] || { echo "FAIL: bundled font is not a TrueType sfnt (header=$MAGIC)" >&2; exit 1; }
grep -qx 'font=LiveBootMono.ttf' "$CONFIG" || { echo "FAIL: settings.conf does not select bundled test font" >&2; exit 1; }
grep -q 'Typeface.Builder(fontFile)' "$RUNNER" || { echo "FAIL: modern Android font loader missing" >&2; exit 1; }
grep -q 'Typeface.createFromFile(fontFile)' "$RUNNER" || { echo "FAIL: compatibility font loader missing" >&2; exit 1; }
grep -q 'liveboot_font_status.log' "$RUNNER" || { echo "FAIL: font diagnostic output missing" >&2; exit 1; }
grep -q 'fonts/LiveBootMono.ttf' "$ROOT/package_module.sh" || { echo "FAIL: package script does not require bundled font" >&2; exit 1; }
[ -s "$ROOT/liveboot_custom/fonts/DEJAVU_FONT_LICENSE.txt" ] || { echo "FAIL: bundled font license missing" >&2; exit 1; }
echo "PASS: bundled TTF asset, default font setting, Android loaders, diagnostics, package inclusion, and font license"
