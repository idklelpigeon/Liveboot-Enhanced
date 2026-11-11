#!/system/bin/sh
# Start LiveBoot at the same early KernelSU/Magisk stage used by the original
# LiveBoot installer. Do not do slow asset reads before launch: boot display is
# the priority. Asset page-cache warm-up runs separately in the background.
MODDIR=/data/adb/modules/liveboot_custom
LOG=/data/local/tmp/liveboot_debug.log
CONFIG="$MODDIR/config/settings.conf"

# ZIP tools and module managers may normalize mode bits; fix executable paths.
/system/bin/chmod 0755 "$MODDIR/post-fs-data.sh" "$MODDIR/service.sh" \
    "$MODDIR/files/liveboot" "$MODDIR/files/libdaemonize.so" 2>/dev/null

echo "===== LIVEBOOT POST-FS-DATA START =====" >> "$LOG"

# Match the original LiveBoot KernelSU/Magisk startup pattern: launch in the
# background during post-fs-data. files/liveboot uses libdaemonize.so to detach
# app_process. Redirect every descriptor so this hook never holds boot open.
(
    "$MODDIR/files/liveboot"
    result=$?
    echo "[+] Early launcher wrapper exited: $result" >> "$LOG"
) >> "$LOG" 2>&1 < /dev/null &

{
    date
    echo "[+] UID / SELinux:"
    id
    /system/bin/getenforce 2>&1
    echo "[+] Early boot properties:"
    /system/bin/getprop init.svc.surfaceflinger 2>&1
    /system/bin/getprop init.svc.bootanim 2>&1
    echo "[+] Asynchronously launched: $MODDIR/files/liveboot"
} >> "$LOG" 2>&1

# Best-effort cache warm-up runs concurrently with the renderer, never delaying
# its first frame. Linux may evict cached pages; this is only an optimization.
(
    MESSAGES="$MODDIR/messages"
    IMAGES="$MODDIR/images"
    FONTS="$MODDIR/fonts"
    APK="$MODDIR/files/liveboot.apk"
    HELPER="$MODDIR/files/libdaemonize.so"
    message_name=default.txt
    font_name=

    if [ -r "$CONFIG" ]; then
        while IFS='=' read -r key value; do
            key=$(echo "$key" | /system/bin/sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            value=$(echo "$value" | /system/bin/sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            case "$key" in
                ''|\#*|\;*) continue ;;
                message)
                    case "$value" in *[!A-Za-z0-9._-]*|'') ;; *) message_name=$value ;; esac
                    ;;
                font)
                    case "$value" in *[!A-Za-z0-9._-]*|'') font_name= ;; *) font_name=$value ;; esac
                    ;;
            esac
        done < "$CONFIG"
    fi

    prewarm() {
        [ -f "$1" ] && [ -r "$1" ] && /system/bin/cat "$1" >/dev/null 2>&1
    }
    prewarm "$APK"
    prewarm "$HELPER"
    case "$message_name" in *[!A-Za-z0-9._-]*|'') exit 0 ;; esac
    message_file="$MESSAGES/$message_name"
    [ -f "$message_file" ] || exit 0
    prewarm "$message_file"
    case "$font_name" in
        *.ttf|*.TTF|*.otf|*.OTF) prewarm "$FONTS/$font_name" ;;
    esac

    while IFS= read -r line || [ -n "$line" ]; do
        trimmed=$(echo "$line" | /system/bin/sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        lower_line=$(echo "$trimmed" | /system/bin/tr '[:upper:]' '[:lower:]')
        case "$lower_line" in
            '[image='*']')
                image_spec=${trimmed#*=}
                image_spec=${image_spec%]}
                image_name=${image_spec%%,*}
                image_name=$(echo "$image_name" | /system/bin/sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
                case "$image_name" in
                    ''|none|clear|*[!A-Za-z0-9._-]*) continue ;;
                    *.png|*.PNG|*.jpg|*.JPG|*.jpeg|*.JPEG|*.webp|*.WEBP)
                        prewarm "$IMAGES/$image_name"
                        ;;
                esac
                ;;
        esac
    done < "$message_file"
) >/dev/null 2>&1 < /dev/null &

# Do not wait for either background task; post-fs-data must remain non-blocking.
exit 0
