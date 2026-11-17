#!/system/bin/sh
DIR="${0%/*}"
[ "$(cat /data/adb/berry_fcm_ksu/enabled 2>/dev/null)" = 1 ] || exit 0
LOCK=/data/adb/berry_fcm_ksu/service.lock
old="$(cat "$LOCK/pid" 2>/dev/null)"
case "$old" in ''|*[!0-9]*) old=0 ;; esac
if [ "$old" -gt 1 ] && [ -r "/proc/$old/cmdline" ]; then
    # Only stop the known Berry guardian service; never arbitrary PIDs.
    if tr '\000' ' ' < "/proc/$old/cmdline" | grep -q '/berry_fcm_ksu/service.sh'; then
        kill "$old" 2>/dev/null || :
        sleep 1
    fi
fi
sh "$DIR/service.sh" </dev/null >/dev/null 2>&1 &
