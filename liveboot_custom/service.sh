#!/system/bin/sh
# Late-stage fallback and diagnostics. The primary launch happens earlier in
# post-fs-data.sh, matching the original LiveBoot startup approach.
LOG=/data/local/tmp/liveboot_debug.log
MODULE=/data/adb/modules/liveboot_custom
LAUNCHER=$MODULE/files/liveboot

{
    echo "===== LIVEBOOT SERVICE STAGE ====="
    date
    echo "[+] UID:"
    id
    echo "[+] SELinux:"
    getenforce 2>&1
    echo "[+] Module files:"
    ls -l "$MODULE" 2>&1
    ls -l "$MODULE/files" 2>&1
    echo "[+] Properties:"
    getprop ro.product.model 2>&1
    getprop ro.build.version.release 2>&1
    getprop ro.build.version.incremental 2>&1
    echo "[+] Boot completed:"
    getprop sys.boot_completed 2>&1
} >> "$LOG" 2>&1

liveboot_running() {
    # --nice-name in files/liveboot sets this Android process name.
    /system/bin/pidof 'eu.chainfire.liveboot:root' >/dev/null 2>&1 && return 0

    # Fallback for Android builds where pidof doesn't report colon names.
    for cmdline in /proc/[0-9]*/cmdline; do
        [ -r "$cmdline" ] || continue
        process_name=$(/system/bin/tr '\000' '\n' < "$cmdline" 2>/dev/null | /system/bin/head -n 1)
        case "$process_name" in
            eu.chainfire.liveboot:root|eu.chainfire.liveboot) return 0 ;;
        esac
    done
    return 1
}

# Give the early post-fs-data launch a short chance to register before fallback.
tries=0
while [ "$tries" -lt 30 ]; do
    if liveboot_running; then
        echo "[+] LiveBoot already running from early boot; refusing duplicate launch." >> "$LOG"
        exit 0
    fi
    /system/bin/sleep 0.1
    tries=$((tries + 1))
done

if liveboot_running; then
    echo "[+] LiveBoot process detected; no fallback needed." >> "$LOG"
    exit 0
fi

echo "[!] Early LiveBoot process not detected; using late-stage fallback." >> "$LOG"
"$LAUNCHER" >> "$LOG" 2>&1
EXIT=$?
echo "[+] Late launcher wrapper exited: $EXIT" >> "$LOG"
