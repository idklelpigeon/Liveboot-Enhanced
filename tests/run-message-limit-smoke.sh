#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RUNNER="$ROOT/liveboot/liveBootAni2/src/main/java/eu/chainfire/liveboot/shell/Runner.java"
GUIDE="$ROOT/MESSAGE_GUIDE.md"
CHANGELOG="$ROOT/CHANGELOG.md"

# Keep the runtime cap at exactly 50 MiB (52,428,800 bytes).
grep -Fqx '    private static final long MAX_MESSAGE_FILE_BYTES = 50L * 1024L * 1024L;' "$RUNNER" || {
    echo "FAIL: runtime message file cap is not 50 MiB" >&2
    exit 1
}
# The byte-size cap and line-count cap are independent safeguards.
grep -Fq 'rawLines.size() >= 10000' "$RUNNER" || {
    echo "FAIL: independent 10,000-line guard is missing" >&2
    exit 1
}
grep -Fq '50 MiB (52,428,800 bytes)' "$GUIDE" || {
    echo "FAIL: message guide does not document the 50 MiB cap" >&2
    exit 1
}
grep -Fq 'independently limited to 10,000 lines' "$GUIDE" || {
    echo "FAIL: message guide does not document the separate line limit" >&2
    exit 1
}
grep -Fq 'Increase the maximum custom message-script file size from 1 MiB to 50 MiB' "$CHANGELOG" || {
    echo "FAIL: changelog entry missing" >&2
    exit 1
}
echo "PASS: custom message scripts accept up to 50 MiB; separate 10,000-line guard retained"
