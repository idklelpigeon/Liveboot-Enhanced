#!/bin/sh
# Build the LiveBoot Enhanced Android runtime and refresh the module APK.
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT="$ROOT/liveboot"
MODULE_APK="$ROOT/liveboot_custom/files/liveboot.apk"
BUILT_APK="$PROJECT/liveBootAni2/build/outputs/apk/debug/liveBootAni2-debug.apk"
BUILD_MARKER="$ROOT/.runtime-build-sha256"

rm -f "$BUILD_MARKER"

# AGP 8.3 requires JDK 17. Gradle 8.4 should not be run on Java 21+.
if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java is missing. Install JDK 17 and place java on PATH." >&2
    exit 1
fi
JAVA_SPEC_VERSION=$(java -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/ {print $3; exit}')
case "$JAVA_SPEC_VERSION" in
    17|18|19|20) ;;
    *)
        echo "ERROR: JDK 17 is required for this Gradle 8.4 build (detected ${JAVA_SPEC_VERSION:-unknown})." >&2
        echo "On Termux: pkg install openjdk-17, then set JAVA_HOME and PATH to its bin directory." >&2
        exit 1
        ;;
esac

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
if [ ! -f "$SDK_DIR/platforms/android-34/android.jar" ]; then
    echo "ERROR: Android SDK platform 34 not found at: $SDK_DIR/platforms/android-34/android.jar" >&2
    echo "Set ANDROID_SDK_ROOT or ANDROID_HOME to the SDK directory." >&2
    exit 1
fi
if [ ! -d "$SDK_DIR/build-tools/34.0.0" ]; then
    echo "ERROR: Android Build Tools 34.0.0 not found under $SDK_DIR/build-tools." >&2
    exit 1
fi
printf 'sdk.dir=%s\n' "$SDK_DIR" > "$PROJECT/local.properties"

if [ ! -x "$PROJECT/gradlew" ]; then
    echo "ERROR: Gradle wrapper is missing or not executable: $PROJECT/gradlew" >&2
    exit 1
fi

# Keep the helper binaries and module startup scripts executable.
chmod 0755 \
    "$ROOT/liveboot_custom/files/liveboot" \
    "$ROOT"/liveboot_custom/files/libdaemonize*.so \
    "$ROOT/liveboot_custom/service.sh" \
    "$ROOT/liveboot_custom/post-fs-data.sh" \
    "$PROJECT/gradlew"

AAPT2_BIN="${AAPT2:-}"
if [ -z "$AAPT2_BIN" ] && command -v aapt2 >/dev/null 2>&1; then
    AAPT2_BIN=$(command -v aapt2)
fi

GRADLE_ARGS=""
if [ -n "$AAPT2_BIN" ]; then
    if [ ! -x "$AAPT2_BIN" ]; then
        echo "ERROR: AAPT2 is not executable: $AAPT2_BIN" >&2
        exit 1
    fi
    echo "Using AAPT2: $AAPT2_BIN"
    GRADLE_ARGS="-Pandroid.aapt2FromMavenOverride=$AAPT2_BIN"
else
    echo "Using the AAPT2 artifact supplied by the Android Gradle Plugin."
fi

(
    cd "$PROJECT"
    # Intentional word splitting passes the optional single -P property to Gradle.
    # shellcheck disable=SC2086
    ./gradlew :liveBootAni2:assembleDebug --no-daemon $GRADLE_ARGS
)

if [ ! -s "$BUILT_APK" ]; then
    echo "ERROR: Gradle completed without producing $BUILT_APK" >&2
    exit 1
fi

cp "$BUILT_APK" "$MODULE_APK"
chmod 0644 "$MODULE_APK"
sha256sum "$MODULE_APK" | awk '{print $1}' > "$BUILD_MARKER"
echo "Updated module runtime: $MODULE_APK"
echo "APK SHA-256: $(cat "$BUILD_MARKER")"
echo "Build complete. Verify boot behavior on the target device before publishing a production release."
