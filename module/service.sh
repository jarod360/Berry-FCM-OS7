#!/system/bin/sh
# KernelSU late_start service. Does nothing if the current boot lost temporary Root.
MODDIR="${0%/*}"
DATA=/data/adb/berry_fcm_ksu
mkdir -p "$DATA" || exit 0
[ "$(id -u)" = 0 ] || exit 0
LOCK="$DATA/service.lock"
MAINTENANCE_LOCK="$DATA/maintenance.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    old="$(cat "$LOCK/pid" 2>/dev/null)"
    case "$old" in ''|*[!0-9]*) old=0 ;; esac
    [ "$old" -gt 1 ] && kill -0 "$old" 2>/dev/null && exit 0
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid"
cgroup_path() {
    while IFS=: read -r hierarchy controllers group; do
        if [ "$hierarchy" = 0 ] && [ -z "$controllers" ]; then
            printf '%s\n' "$group"
            return
        fi
    done < "/proc/$$/cgroup"
}
group="$(cgroup_path)"
cgroup_ready=1
case "$group" in
    /apps/*)
        # Hot install and WebUI can inherit the Manager's app freezer group.
        # Move only this service; workers inherit the verified group.
        if { printf '%s\n' "$$" > /sys/fs/cgroup/cgroup.procs; } 2>/dev/null &&
           [ "$(cgroup_path)" = / ]; then
            printf '[%s] service_cgroup_detached pid=%s from=%s to=/\n' "$(date '+%F %T')" "$$" "$group" >> "$DATA/guard.log"
        else
            cgroup_ready=0
            printf '[%s] service_cgroup_detach_failed pid=%s group=%s\n' "$(date '+%F %T')" "$$" "$group" >> "$DATA/guard.log"
        fi
        ;;
esac
wait_pid=
timer_pid=
service_pid=$$
mode_changed=0
wake_count=0
wake_seen=0
stop_wait() {
    [ -z "$wait_pid" ] || kill "$wait_pid" 2>/dev/null || :
}
stop_timer() {
    [ -n "$timer_pid" ] || return 0
    kill "$timer_pid" 2>/dev/null || :
    wait "$timer_pid" 2>/dev/null || :
    timer_pid=
}
stop_service() {
    stop_wait
    stop_timer
    exit 0
}
refresh_mode() {
    mode_changed=1
    stop_wait
}
on_timer() {
    wake_count=$((wake_count + 1))
    stop_wait
}
trap 'rm -rf "$LOCK"' EXIT
trap stop_service HUP INT TERM
trap refresh_mode USR1
trap on_timer USR2
echo $$ > "$LOCK/wake-pid"

timer_worker() {
    # The parent owns the service lock. This worker only owns the native timer.
    trap - EXIT USR1 USR2
    alarm_pid=
    log="$DATA/wake-timer.log"
    printf '[%s] timer_worker_started service_pid=%s interval_min=%s\n' \
        "$(date -u '+%FT%TZ')" "$service_pid" "$1" >> "$log"
    timer_worker_cleanup() {
        worker_rc=$?
        if [ -n "$alarm_pid" ]; then
            kill "$alarm_pid" 2>/dev/null || :
            wait "$alarm_pid" 2>/dev/null || :
        fi
        printf '[%s] timer_worker_exited service_pid=%s rc=%s\n' \
            "$(date -u '+%FT%TZ')" "$service_pid" "$worker_rc" >> "$log"
    }
    trap timer_worker_cleanup EXIT
    trap 'exit 0' HUP INT TERM
    echo timed > "$DATA/wake-status"
    while [ -e "$MODDIR/module.prop" ] && kill -0 "$service_pid" 2>/dev/null; do
        "$MODDIR/bin/wake-timer-arm64" "$1" >> "$log" 2>&1 &
        alarm_pid=$!
        if ! wait "$alarm_pid"; then
            alarm_pid=
            echo fallback > "$DATA/wake-status"
            printf "[%s] 定时器失败，常规巡检继续\n" "$(date "+%F %T")" >> "$log"
            return 1
        fi
        alarm_pid=
        kill -USR2 "$service_pid" 2>/dev/null || return 0
        tail -n 120 "$log" > "$log.tmp" && mv -f "$log.tmp" "$log"
    done
}

sync_timer() {
    stop_timer
    if [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ] &&
       [ "$(cat "$DATA/wake-mode" 2>/dev/null)" = timed ]; then
        if [ "$cgroup_ready" = 1 ]; then
            minutes="$(cat "$DATA/wake-minutes" 2>/dev/null)"
            case "$minutes" in [1-9]|10) :;; *) minutes=3;; esac
            timer_worker "$minutes" &
            timer_pid=$!
        else
            echo cgroup_failed > "$DATA/wake-status"
        fi
    else
        echo passive > "$DATA/wake-status"
    fi
}

# Wait for complete startup, no hard dependency on Shizuku.
n=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$n" -lt 120 ]; do
    sleep 3; n=$((n + 1))
done
printf '[%s] service_started pid=%s wake_mode=%s enabled=%s cgroup_ready=%s\n' \
    "$(date '+%F %T')" "$$" "$(cat "$DATA/wake-mode" 2>/dev/null || echo passive)" \
    "$(cat "$DATA/enabled" 2>/dev/null || echo 0)" "$cgroup_ready" >> "$DATA/guard.log"
# The 30s sleep does not wake a suspended CPU. Resume with a fresh GMS read.
last_fix=0
mode_changed=1
while [ -e "$MODDIR/module.prop" ]; do
    if [ "$mode_changed" = 1 ]; then
        mode_changed=0
        sync_timer
    fi
    wake_event=0
    pending="$wake_count"
    if [ "$pending" != "$wake_seen" ]; then
        wake_event=1
        wake_seen="$pending"
    fi
    if [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ]; then
        now="$(awk '{print int($1)}' /proc/uptime)"
        case "$now" in ''|*[!0-9]*) now=0 ;; esac
        # Read the native GMS state; TCP sockets are auxiliary evidence.
        # It never restarts/kills GMS and never changes DNS/VPN/proxy/routing.
        if [ "$wake_event" = 1 ]; then
            printf '[%s] 定时器触发，检查 GMS 原生状态\n' "$(date '+%F %T')" >> "$DATA/guard.log"
        fi
        check_started_epoch="$(date +%s 2>/dev/null)"
        case "$check_started_epoch" in ''|*[!0-9]*) check_started_epoch=0;; esac
        check_wake_mode="$(cat "$DATA/wake-mode" 2>/dev/null || echo passive)"
        printf '[%s] fcm_check_start pid=%s uptime=%s wake=%s mode=%s\n' \
            "$(date '+%F %T')" "$$" "$now" "$wake_event" "$check_wake_mode" >> "$DATA/guard.log"
        result="$(sh "$MODDIR/bin/fcm-monitor.sh" monitor 2>&1)"
        monitor_rc=$?
        sample="$(sh "$MODDIR/bin/fcm-monitor.sh" snapshot 2>/dev/null)"
        summary="$(printf '%s\n' "$sample" | sed -n 's/.*"fcm_state":"\([a-z_]*\)".*"fcm_sample_time":\([0-9]*\).*"fcm_recovery_decision":"\([a-z_]*\)".*/state=\1 sample_epoch=\2 recovery=\3/p')"
        if [ "$wake_event" = 1 ]; then
            printf '[%s] wake_gms_check_finished rc=%s %s\n' "$(date '+%F %T')" "$monitor_rc" "${summary:-snapshot=unavailable}" >> "$DATA/guard.log"
        fi
        check_finished_epoch="$(date +%s 2>/dev/null)"
        case "$check_finished_epoch" in ''|*[!0-9]*) check_finished_epoch=0;; esac
        check_elapsed=unknown
        if [ "$check_started_epoch" -gt 0 ] && [ "$check_finished_epoch" -ge "$check_started_epoch" ]; then
            check_elapsed=$((check_finished_epoch-check_started_epoch))
        fi
        printf '[%s] fcm_check_end rc=%s elapsed_s=%s %s\n' \
            "$(date '+%F %T')" "$monitor_rc" "$check_elapsed" "${summary:-snapshot=unavailable}" >> "$DATA/guard.log"
        [ -z "$result" ] || printf '[%s] %s\n' "$(date '+%F %T')" "$result" >> "$DATA/guard.log"
        if [ "$last_fix" = 0 ] || [ "$now" -lt "$last_fix" ] || [ $((now - last_fix)) -ge 300 ]; then
            if [ -d "$MAINTENANCE_LOCK" ]; then
                old="$(cat "$MAINTENANCE_LOCK/pid" 2>/dev/null)"
                case "$old" in ''|*[!0-9]*) old=0;; esac
                if [ "$old" -gt 1 ] && kill -0 "$old" 2>/dev/null; then
                    printf '[%s] maintenance_skip pid=%s reason=already_running\n' "$(date '+%F %T')" "$old" >> "$DATA/guard.log"
                else
                    rm -rf "$MAINTENANCE_LOCK"
                fi
            fi
            if [ ! -d "$MAINTENANCE_LOCK" ] && mkdir "$MAINTENANCE_LOCK" 2>/dev/null; then
                (
                    trap 'rm -rf "$MAINTENANCE_LOCK"' EXIT
                    fix_started_epoch="$(date +%s 2>/dev/null)"
                    case "$fix_started_epoch" in ''|*[!0-9]*) fix_started_epoch=0;; esac
                    printf '[%s] maintenance_start pid=%s uptime=%s operation=guard_fix\n' \
                        "$(date '+%F %T')" "$" "$now" >> "$DATA/guard.log"
                    HEARTBEAT_MODE=auto sh "$MODDIR/bin/ctl.sh" fix >> "$DATA/guard.log" 2>&1
                    fix_rc=$?
                    fix_finished_epoch="$(date +%s 2>/dev/null)"
                    case "$fix_finished_epoch" in ''|*[!0-9]*) fix_finished_epoch=0;; esac
                    fix_elapsed=unknown
                    if [ "$fix_started_epoch" -gt 0 ] && [ "$fix_finished_epoch" -ge "$fix_started_epoch" ]; then
                        fix_elapsed=$((fix_finished_epoch-fix_started_epoch))
                    fi
                    printf '[%s] maintenance_end pid=%s operation=guard_fix rc=%s elapsed_s=%s\n' \
                        "$(date '+%F %T')" "$" "$fix_rc" "$fix_elapsed" >> "$DATA/guard.log"
                    exit "$fix_rc"
                ) &
                maintenance_pid=$!
                printf '%s\n' "$maintenance_pid" > "$MAINTENANCE_LOCK/pid"
            fi
            last_fix="$now"
        fi
        bytes="$(wc -c < "$DATA/guard.log" 2>/dev/null)"
        case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
        if [ "$bytes" -gt 131072 ]; then
            tail -n 160 "$DATA/guard.log" > "$DATA/guard.log.tmp" && mv -f "$DATA/guard.log.tmp" "$DATA/guard.log"
        fi
    fi
    if [ "$mode_changed" = 1 ] || [ "$wake_count" != "$wake_seen" ]; then continue; fi
    sleep 30 &
    wait_pid=$!
    if [ "$mode_changed" = 1 ] || [ "$wake_count" != "$wake_seen" ]; then stop_wait; fi
    wait "$wait_pid"
    wait_pid=
done
stop_timer
