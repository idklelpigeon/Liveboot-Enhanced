#!/bin/sh
# Package the freshly built module payload as a KernelSU/Magisk ZIP.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MODULE="$ROOT/liveboot_custom"
OUT="${1:-$HOME/storage/downloads/LiveBoot_Enhanced_v1.0.zip}"
BUILD_MARKER="$ROOT/.runtime-build-sha256"

if [ ! -s "$BUILD_MARKER" ]; then
    echo "ERROR: no successful local build marker. Run ./build_module.sh successfully first." >&2
    exit 1
fi
if [ ! -s "$MODULE/files/liveboot.apk" ]; then
    echo "ERROR: module runtime APK is missing. Run ./build_module.sh first." >&2
    exit 1
fi
CURRENT_SHA=$(sha256sum "$MODULE/files/liveboot.apk" | awk '{print $1}')
BUILT_SHA=$(cat "$BUILD_MARKER")
if [ "$CURRENT_SHA" != "$BUILT_SHA" ]; then
    echo "ERROR: module APK changed after compilation. Run ./build_module.sh again." >&2
    exit 1
fi

for required in module.prop post-fs-data.sh service.sh files/liveboot files/libdaemonize.so config/settings.conf images messages fonts README.md; do
    if [ ! -e "$MODULE/$required" ]; then
        echo "ERROR: required module path is missing: $required" >&2
        exit 1
    fi
done

mkdir -p "$(dirname -- "$OUT")"
chmod 0755 "$MODULE/post-fs-data.sh" "$MODULE/service.sh" "$MODULE/files/liveboot" "$MODULE"/files/libdaemonize*.so
chmod 0644 "$MODULE/module.prop" "$MODULE/files/liveboot.apk" "$MODULE/config/settings.conf"
find "$MODULE/fonts" -type d -exec chmod 0755 {} \;
find "$MODULE/fonts" -type f -exec chmod 0644 {} \;
rm -f "$OUT"
(
    cd "$MODULE"
    zip -r -9 "$OUT" module.prop post-fs-data.sh service.sh config files fonts images messages README.md
)

unzip -t "$OUT"
for required in module.prop post-fs-data.sh service.sh files/liveboot files/liveboot.apk files/libdaemonize.so config/settings.conf messages/default.txt images/README.md fonts/LiveBootMono.ttf fonts/DEJAVU_FONT_LICENSE.txt; do
    if ! unzip -Z -1 "$OUT" | grep -Fxq "$required"; then
        echo "ERROR: package is missing required entry: $required" >&2
        exit 1
    fi
done
for executable in post-fs-data.sh service.sh files/liveboot files/libdaemonize.so files/libdaemonize.arm64-v8a.so files/libdaemonize.armeabi-v7a.so files/libdaemonize.x86.so files/libdaemonize.x86_64.so; do
    if ! zipinfo -l "$OUT" | grep -F "$executable" | grep -q 'rwx'; then
        echo "ERROR: $executable is not executable in the ZIP; refusing to call it ready." >&2
        exit 1
    fi
done
echo "Module ZIP created and verified: $OUT"
echo "APK SHA-256: $CURRENT_SHA"
