#!/system/bin/sh
# KernelSU late_start service. Does nothing if the current boot lost temporary Root.
MODDIR="${0%/*}"
DATA=/data/adb/berry_fcm_ksu
mkdir -p "$DATA" || exit 0
[ "$(id -u)" = 0 ] || exit 0
LOCK="$DATA/service.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    old="$(cat "$LOCK/pid" 2>/dev/null)"
    case "$old" in ''|*[!0-9]*) old=0 ;; esac
    [ "$old" -gt 1 ] && kill -0 "$old" 2>/dev/null && exit 0
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid"
wait_pid=
mode_changed=0
stop_wait() {
    [ -z "$wait_pid" ] || kill "$wait_pid" 2>/dev/null || :
}
stop_service() {
    stop_wait
    exit 0
}
refresh_mode() {
    mode_changed=1
    stop_wait
}
trap 'rm -rf "$LOCK"' EXIT
trap stop_service HUP INT TERM
trap refresh_mode USR1
echo $$ > "$LOCK/wake-pid"
wait_for() {
    "$@" &
    wait_pid=$!
    [ "$mode_changed" != 1 ] || stop_wait
    wait "$wait_pid"
    wait_rc=$?
    wait_pid=
    return "$wait_rc"
}
# Wait for complete startup, no hard dependency on Shizuku.
n=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$n" -lt 120 ]; do
    sleep 3; n=$((n + 1))
done
# The 30s sleep does not wake a suspended CPU. Resume with a fresh GMS read.
last_fix=0
wake_event=0
timer_failed=0
while [ -e "$MODDIR/module.prop" ]; do
    if [ "$mode_changed" = 1 ]; then
        mode_changed=0
        timer_failed=0
    fi
    if [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ]; then
        now="$(awk '{print int($1)}' /proc/uptime)"
        case "$now" in ''|*[!0-9]*) now=0 ;; esac
        # Read the native GMS state; TCP sockets are auxiliary evidence.
        # It never restarts/kills GMS and never changes DNS/VPN/proxy/routing.
        if [ "$wake_event" = 1 ]; then
            printf '[%s] 3 分钟定时器触发，检查 GMS 原生状态\n' "$(date '+%F %T')" >> "$DATA/guard.log"
        fi
        result="$(sh "$MODDIR/bin/fcm-monitor.sh" monitor 2>&1)"
        monitor_rc=$?
        if [ "$wake_event" = 1 ]; then
            sample="$(sh "$MODDIR/bin/fcm-monitor.sh" snapshot 2>/dev/null)"
            summary="$(printf '%s\n' "$sample" | sed -n 's/.*"fcm_state":"\([a-z_]*\)".*"fcm_sample_time":\([0-9]*\).*"fcm_recovery_decision":"\([a-z_]*\)".*/state=\1 sample_epoch=\2 recovery=\3/p')"
            printf '[%s] wake_gms_check_finished rc=%s %s\n' "$(date '+%F %T')" "$monitor_rc" "${summary:-snapshot=unavailable}" >> "$DATA/guard.log"
        fi
        [ -z "$result" ] || printf '[%s] %s\n' "$(date '+%F %T')" "$result" >> "$DATA/guard.log"
        if [ "$last_fix" = 0 ] || [ "$now" -lt "$last_fix" ] || [ $((now - last_fix)) -ge 300 ]; then
            echo "[$(date '+%F %T')] stopped 巡检" >> "$DATA/guard.log"
            HEARTBEAT_MODE=auto sh "$MODDIR/bin/ctl.sh" fix >> "$DATA/guard.log" 2>&1
            last_fix="$now"
        fi
        bytes="$(wc -c < "$DATA/guard.log" 2>/dev/null)"
        case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
        if [ "$bytes" -gt 131072 ]; then
            tail -n 160 "$DATA/guard.log" > "$DATA/guard.log.tmp" && mv -f "$DATA/guard.log.tmp" "$DATA/guard.log"
        fi
    fi
    wake_event=0
    [ "$mode_changed" != 1 ] || continue
    if [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ] && [ "$(cat "$DATA/wake-mode" 2>/dev/null)" = timed ]; then
        if [ "$timer_failed" = 0 ]; then
            echo timed > "$DATA/wake-status"
            if wait_for "$MODDIR/bin/wake-timer-arm64" >> "$DATA/guard.log" 2>&1; then
                wake_event=1
                continue
            fi
            [ "$mode_changed" != 1 ] || continue
            timer_failed=1
            printf '[%s] 定时器失败，切回随系统唤醒\n' "$(date '+%F %T')" >> "$DATA/guard.log"
        fi
        echo fallback > "$DATA/wake-status"
    else
        echo passive > "$DATA/wake-status"
    fi
    wait_for sleep 30
done
