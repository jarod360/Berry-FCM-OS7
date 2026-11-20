#!/system/bin/sh
DIR="$(cd "${0%/*}" && pwd)" || exit 1
[ "$(cat /data/adb/berry_fcm_ksu/enabled 2>/dev/null)" = 1 ] || exit 0
LOCK=/data/adb/berry_fcm_ksu/service.lock
old="$(cat "$LOCK/pid" 2>/dev/null)"
case "$old" in ''|*[!0-9]*) old=0 ;; esac
is_guardian() {
    [ "$old" -gt 1 ] && [ -r "/proc/$old/cmdline" ] &&
        tr '\000' ' ' < "/proc/$old/cmdline" | grep -q '/berry_fcm_ksu/service.sh '
}
if is_guardian; then
    kill "$old" 2>/dev/null || :
    n=0
    while is_guardian && [ "$n" -lt 4 ]; do
        sleep 1
        n=$((n + 1))
    done
    # 0.5.7 and older could return from their TERM trap and continue.
    if is_guardian && [ "$(cat "$LOCK/wake-pid" 2>/dev/null)" != "$old" ]; then
        kill -KILL "$old" 2>/dev/null || :
        sleep 1
    fi
fi
sh "$DIR/service.sh" </dev/null >/dev/null 2>&1 &
