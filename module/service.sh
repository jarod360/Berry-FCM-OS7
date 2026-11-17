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
trap 'rm -rf "$LOCK"' EXIT HUP INT TERM
# Wait for complete startup, no hard dependency on Shizuku.
n=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$n" -lt 120 ]; do
    sleep 3; n=$((n + 1))
done
# Preserve the existing stopped-state repair, but monitor FCM at 30s cadence.
# sleep can be delayed by deep Doze: 2m is an active-time target, not a strict
# 5m wall-clock guarantee while the CPU is suspended.
last_fix=0
while [ -e "$MODDIR/module.prop" ]; do
    if [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ]; then
        now="$(awk '{print int($1)}' /proc/uptime)"
        case "$now" in ''|*[!0-9]*) now=0 ;; esac
        if [ "$last_fix" = 0 ] || [ "$now" -lt "$last_fix" ] || [ $((now - last_fix)) -ge 300 ]; then
            echo "[$(date '+%F %T')] stopped 巡检" >> "$DATA/guard.log"
            HEARTBEAT_MODE=auto sh "$MODDIR/bin/ctl.sh" fix >> "$DATA/guard.log" 2>&1
            last_fix="$now"
        fi
        # Lifecycle monitor observes GMS-owned 5228-5230 and trusted mtalk:443 sockets.
        # It never restarts/kills GMS and never changes DNS/VPN/proxy/routing.
        result="$(sh "$MODDIR/bin/fcm-monitor.sh" monitor 2>&1)"
        [ -z "$result" ] || printf '[%s] %s\n' "$(date '+%F %T')" "$result" >> "$DATA/guard.log"
        bytes="$(wc -c < "$DATA/guard.log" 2>/dev/null)"
        case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
        if [ "$bytes" -gt 131072 ]; then
            tail -n 160 "$DATA/guard.log" > "$DATA/guard.log.tmp" && mv -f "$DATA/guard.log.tmp" "$DATA/guard.log"
        fi
    fi
    sleep 30
done
