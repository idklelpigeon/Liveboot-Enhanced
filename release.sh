#!/bin/sh
# Run the parser checks, build the Android runtime, and package the module ZIP.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

"$ROOT/tests/run-parser-smoke.sh"
"$ROOT/tests/run-font-smoke.sh"
"$ROOT/build_module.sh"
"$ROOT/package_module.sh" "${1:-$HOME/storage/downloads/LiveBoot_Enhanced_v1.0.zip}"
