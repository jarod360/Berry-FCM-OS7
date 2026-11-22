#!/system/bin/sh
# BerryFCM KernelSU (UID 0): GMS MCS state is observed directly from procfs TCP tables.
# Only observes GMS-owned TCP sockets and requests targeted recovery broadcasts; it never
# changes DNS, VPN, proxy, routing, or force-stops Google Play services.
set -u

GMS=com.google.android.gms
DATA="${BERRY_FCM_DATA:-/data/adb/berry_fcm_ksu}"
STATE="$DATA/mcs-monitor.state"
EVENTS="$DATA/mcs-monitor-events.log"
NATIVE_EVENTS="$DATA/mcs-native-events.log"
EVIDENCE="$DATA/mcs-evidence"
CMD_BIN="${BERRY_CMD_BIN:-cmd}"
PM_BIN="${BERRY_PM_BIN:-pm}"
AM_BIN="${BERRY_AM_BIN:-am}"
if [ -x /system/bin/am ] && [ -z "${BERRY_AM_BIN:-}" ]; then AM_BIN=/system/bin/am; fi
UPTIME_FILE="${BERRY_UPTIME_FILE:-/proc/uptime}"
BOOT_ID_FILE="${BERRY_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}"
PROC_TCP="${BERRY_PROC_NET_TCP:-/proc/net/tcp}"
PROC_TCP6="${BERRY_PROC_NET_TCP6:-/proc/net/tcp6}"
KSU_BUSYBOX="${BERRY_KSU_BUSYBOX:-/data/adb/ksu/bin/busybox}"

[ "$(id -u)" = 0 ] || { echo "ERROR: 需要 KernelSU Root (UID 0)" >&2; exit 10; }
mkdir -p "$DATA" || exit 11

is_uint() { case "${1:-}" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
num() { is_uint "${1:-}" && printf '%s\n' "$1" || printf '0\n'; }
signed_num() {
    case "${1:-}" in
        -[0-9]*)
            sn="${1#-}"
            case "$sn" in ''|*[!0-9]*) printf '0\n';; *) printf '%s\n' "-$sn";; esac
            ;;
        *) num "${1:-}" ;;
    esac
}
now_epoch() { date +%s 2>/dev/null | awk '/^[0-9]+$/ {print; found=1} END {if (!found) print 0}'; }
monotime() { awk '{printf "%d\n", $1}' "$UPTIME_FILE" 2>/dev/null | head -n 1; }
boot_id() { cat "$BOOT_ID_FILE" 2>/dev/null; }

run_bounded() {
    if command -v timeout >/dev/null 2>&1; then
        timeout "$@"
    elif [ -x "$KSU_BUSYBOX" ] && "$KSU_BUSYBOX" --list 2>/dev/null | grep -qx timeout; then
        "$KSU_BUSYBOX" timeout "$@"
    else
        return 127
    fi
}

# Persisted fields use a fixed pipe-delimited schema. Never source the file.
load_state() {
    b="$(boot_id)"
    state=never_seen; conn_key=; conn_since=0; conn_since_mono=0; last_seen=0
    missing_since=0; missing_epoch=0; last_try_mono=0; last_try_epoch=0
    attempts=0; last_result=none; last_restore=0; outages=0
    transport=; remote=; sample_epoch=0; last_try_reason=none
    reconnects=0; last_down=0
    down_observed=0; retry_streak=0; outage_hint_mono=0
    native_timer_duration=0; native_timer_since=0; native_timer_last_request=0; native_timer_key=
    prev_b=
    if [ -f "$STATE" ]; then
        IFS='|' read -r prev_b state conn_key conn_since conn_since_mono last_seen missing_since missing_epoch last_try_mono last_try_epoch attempts last_result last_restore outages transport remote sample_epoch last_try_reason reconnects last_down down_observed retry_streak outage_hint_mono native_timer_duration native_timer_since native_timer_last_request native_timer_key < "$STATE" || :
    fi
    conn_since="$(num "$conn_since")"; conn_since_mono="$(num "$conn_since_mono")"; last_seen="$(num "$last_seen")"
    missing_since="$(num "$missing_since")"; missing_epoch="$(num "$missing_epoch")"
    last_try_mono="$(signed_num "$last_try_mono")"; last_try_epoch="$(num "$last_try_epoch")"
    attempts="$(num "$attempts")"; last_restore="$(num "$last_restore")"
    outages="$(num "$outages")"; sample_epoch="$(num "$sample_epoch")"
    reconnects="$(num "$reconnects")"; last_down="$(num "$last_down")"
    down_observed="$(num "${down_observed:-0}")"; retry_streak="$(num "${retry_streak:-0}")"
    outage_hint_mono="$(num "${outage_hint_mono:-0}")"
    native_timer_duration="$(num "${native_timer_duration:-0}")"
    native_timer_since="$(num "${native_timer_since:-0}")"
    native_timer_last_request="$(num "${native_timer_last_request:-0}")"
    case "$state" in connected|connecting|disconnected|unknown|never_seen) : ;; *) state=unknown;; esac
    case "$last_result" in none|pending|requested|failed|restored) : ;; *) last_result=none;; esac
    case "$last_try_reason" in none|auto|manual|rescue) : ;; *) last_try_reason=none;; esac
    boot_changed=0
    if [ -n "$prev_b" ] && [ "$prev_b" != "$b" ]; then
        boot_changed=1
        state=never_seen; conn_key=; conn_since=0; conn_since_mono=0; missing_since=0; missing_epoch=0
        last_try_mono=0
        transport=; remote=; sample_epoch=0; down_observed=0; outage_hint_mono=0
        # Legacy timer columns are retained in the state schema only for
        # compatibility; they are never read as connection-state evidence.
        native_timer_duration=0; native_timer_since=0; native_timer_last_request=0; native_timer_key=
        record_event reboot '设备重启：清除上一启动周期的连接计时，保留历史事件'
    fi
    # Re-anchor the prior attempt to this boot's monotonic clock. Store a
    # negative offset when the epoch-derived anchor predates uptime zero; -1
    # encodes an exact anchor of zero, while zero means no monotonic anchor.
    if [ "$last_try_epoch" -gt 0 ] && { [ "$boot_changed" = 1 ] || [ "$last_try_mono" -eq 0 ]; }; then
        boot_now_epoch="$(num "$(now_epoch)")"
        boot_now_mono="$(num "$(monotime)")"
        if [ "$boot_now_epoch" -gt 0 ] && [ "$boot_now_epoch" -ge "$last_try_epoch" ]; then
            boot_elapsed=$((boot_now_epoch-last_try_epoch))
            boot_anchor=$((boot_now_mono-boot_elapsed))
        else
            # Wall clock is unavailable or moved backwards: wait one full
            # interval from this boot, but never depend on it catching up.
            boot_anchor="$boot_now_mono"
        fi
        if [ "$boot_anchor" -ge 1 ]; then last_try_mono="$boot_anchor"
        else last_try_mono=$((boot_anchor-1)); fi
    elif [ "$boot_changed" = 1 ] && [ "$retry_streak" -gt 0 ]; then
        boot_now_mono="$(num "$(monotime)")"
        if [ "$boot_now_mono" -ge 1 ]; then last_try_mono="$boot_now_mono"
        else last_try_mono=-1; fi
    fi
}

save_state() {
    tmp="$STATE.$$"
    [ ! -d "$STATE" ] || return 1
    old_umask="$(umask)"
    umask 077
    printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
        "$b" "$state" "$conn_key" "$conn_since" "$conn_since_mono" "$last_seen" "$missing_since" "$missing_epoch" \
        "$last_try_mono" "$last_try_epoch" "$attempts" "$last_result" "$last_restore" "$outages" \
        "$transport" "$remote" "$sample_epoch" "$last_try_reason" "$reconnects" "$last_down" "$down_observed" "$retry_streak" "$outage_hint_mono" \
        "$native_timer_duration" "$native_timer_since" "$native_timer_last_request" "$native_timer_key" > "$tmp"
    write_rc=$?
    umask "$old_umask"
    [ "$write_rc" -eq 0 ] || { rm -f "$tmp"; return 1; }
    [ -s "$tmp" ] && [ ! -d "$STATE" ] || { rm -f "$tmp"; return 1; }
    awk -F '|' 'NR == 1 && NF == 27 { valid=1 } END { exit !(valid && NR == 1) }' "$tmp" || {
        rm -f "$tmp"
        return 1
    }
    mv -f "$tmp" "$STATE" || { rm -f "$tmp"; return 1; }
    [ -f "$STATE" ] || return 1
}


record_event() {
    e="${1:-event}"; msg="${2:-}"
    msg="$(printf '%s' "$msg" | tr '\r\n|' '   ' | cut -c1-220)"
    printf '%s|%s|%s\n' "$(now_epoch)" "$e" "$msg" >> "$EVENTS"
    lines="$(wc -l < "$EVENTS" 2>/dev/null | tr -d ' ')"; lines="$(num "$lines")"
    if [ "$lines" -gt 120 ]; then
        tail -n 120 "$EVENTS" > "$EVENTS.tmp" && mv -f "$EVENTS.tmp" "$EVENTS"
    fi
}

gms_uid() {
    uid_reason=uid_unavailable
    uid_output="$(run_bounded 5 "$CMD_BIN" package list packages -U "$GMS" 2>/dev/null)"
    cmd_rc=$?
    uid=
    if [ "$cmd_rc" -eq 0 ]; then
        uid="$(printf '%s\n' "$uid_output" |
          awk -v p="$GMS" '$1=="package:"p {for(i=2;i<=NF;i++)if($i~/^uid:[0-9]+$/){v=$i;sub(/^uid:/,"",v);if(!seen[v]++){u=v;n++}}} END{if(n==1)print u;else exit 1}')"
    fi
    if ! is_uint "$uid"; then
        uid_output="$(run_bounded 5 "$PM_BIN" list packages -U "$GMS" 2>/dev/null)"
        pm_rc=$?
        uid=
        if [ "$pm_rc" -eq 0 ]; then
            uid="$(printf '%s\n' "$uid_output" |
              awk -v p="$GMS" '$1=="package:"p {for(i=2;i<=NF;i++)if($i~/^uid:[0-9]+$/){v=$i;sub(/^uid:/,"",v);if(!seen[v]++){u=v;n++}}} END{if(n==1)print u;else exit 1}')"
        fi
        if ! is_uint "$uid" && { [ "$cmd_rc" -eq 124 ] || [ "$pm_rc" -eq 124 ]; }; then
            uid_reason=uid_timeout
        fi
    fi
    if ! is_uint "$uid"; then
        printf '%s\n' "$uid_reason"
        return 1
    fi
    printf '%s\n' "$uid"
}
scan_proc_table() {
    file="$1"; family="$2"; wanted_uid="$3"
    [ -r "$file" ] || { printf 'unavailable|unknown|%s_unreadable\n' "$family"; return 0; }
    result="$(awk -v family="$family" -v wanted_uid="$wanted_uid" '
      BEGIN { endpoint="^[[:xdigit:]]+:[[:xdigit:]]+$" }
      NR==1 {
        if(NF!=12 || $1!="sl" || $2!="local_address" || $3!="rem_address" || $4!="st" ||
           $5!="tx_queue" || $6!="rx_queue" || $7!="tr" || $8!="tm->when" ||
           $9!="retrnsmt" || $10!="uid" || $11!="timeout" || $12!="inode") bad=1
        else header=1
        next
      }
      {
        bytes += length($0)+1
        if(NR>2048 || bytes>262144 || length($0)>1024) { truncated=1; exit }
        # Linux proc TCP data rows collapse the paired header columns:
        # tx_queue:rx_queue and tr:tm->when, so rows contain 10 fields.
        if(NF!=10 || $1!~/^[0-9]+:$/ || $2!~endpoint || $3!~endpoint ||
           (family=="tcp" && (length($2)!=13 || length($3)!=13 || index($2,":")!=9 || index($3,":")!=9)) ||
           (family=="tcp6" && (length($2)!=37 || length($3)!=37 || index($2,":")!=33 || index($3,":")!=33)) ||
           length($4)!=2 || toupper($4)!~/^(01|02|03|04|05|06|07|08|09|0A|0B|0C)$/ ||
           $5!~/^[[:xdigit:]]+:[[:xdigit:]]+$/ || length($5)!=17 || index($5,":")!=9 ||
           $6!~/^[[:xdigit:]]+:[[:xdigit:]]+$/ || length($6)!=11 || index($6,":")!=3 ||
           length($7)!=8 || $7!~/^[[:xdigit:]]+$/ ||
           $8!~/^[0-9]+$/ || $9!~/^[0-9]+$/ || $10!~/^[0-9]+$/) {
            bad=1
            next
        }
        p=tolower($3); sub(/^.*:/,"",p)
        if($8==wanted_uid && (p=="146c" || p=="146d" || p=="146e")) {
          state=toupper($4)
          if(state=="01") up=1
          else if(state=="02" || state=="03") syn=1
        }
      }
      END {
        if(truncated) print "truncated|unknown|" family "_truncated"
        else if(!header || bad) print "malformed|unknown|" family "_malformed"
        else if(up) print "complete|established|none"
        else if(syn) print "complete|connecting|none"
        else print "complete|absent|none"
      }' "$file" 2>/dev/null)"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        printf 'unavailable|unknown|%s_read_error\n' "$family"
    elif [ -n "$result" ]; then
        printf '%s\n' "$result"
    else
        printf 'unavailable|unknown|%s_empty_result\n' "$family"
    fi
}
scan_sockets() {
    uid="$(gms_uid 2>/dev/null)" || {
        case "$uid" in uid_timeout) reason=uid_timeout;; *) reason=uid_unavailable;; esac
        printf 'unknown||||%s\n' "$reason"
        return
    }
    a="$(scan_proc_table "$PROC_TCP" tcp "$uid")"
    b="$(scan_proc_table "$PROC_TCP6" tcp6 "$uid")"
    IFS='|' read -r ca sa ra <<EOF_A
$a
EOF_A
    IFS='|' read -r cb sb rb <<EOF_B
$b
EOF_B
    if [ "$sa" = established ] || [ "$sb" = established ]; then
        printf 'connected|tcp|MCS|gms:%s|procfs_established\n' "$uid"
    elif [ "$sa" = connecting ] || [ "$sb" = connecting ]; then
        printf 'connecting|tcp|MCS|gms:%s|procfs_syn_state\n' "$uid"
    elif [ "$ca" = complete ] && [ "$cb" = complete ]; then
        printf 'absent||||procfs_complete_no_mcs_socket\n'
    else
        if [ "$ca" != complete ] && [ -n "$ra" ]; then reason="$ra"
        elif [ "$cb" != complete ] && [ -n "$rb" ]; then reason="$rb"
        else reason=procfs_incomplete
        fi
        printf 'unknown||||%s\n' "$reason"
    fi
}

# Procfs is the sole connection-state source in the KernelSU monitor.
auto_enabled() {
    [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ]
}

retry_interval() {
    if [ "$last_try_reason" = manual ] &&
         { [ "$last_result" = pending ] || [ "$last_result" = requested ]; }; then
        echo 60
    else
        case "$retry_streak" in 0) echo 30;; 1) echo 60;; 2) echo 120;; *) echo 180;; esac
    fi
}

# Reconstruct a monotonic anchor from epoch once after reboot. Thereafter
# cooldown is measured only by monotonic time, so later wall-clock jumps cannot
# bypass it or keep extending it. A backwards/unavailable boot clock starts a
# bounded full interval on the new monotonic clock.
cooldown_remaining() {
    cd_interval="$1"
    cd_nowm="$(num "$(monotime)")"
    cd_anchor=0
    case "$last_try_mono" in
        -*) cd_anchor=$((last_try_mono+1));;
        *) cd_anchor="$last_try_mono";;
    esac
    if [ "$last_try_mono" -ne 0 ] && [ "$cd_nowm" -ge "$cd_anchor" ]; then
        cd_elapsed=$((cd_nowm-cd_anchor))
    elif [ "$last_try_epoch" -gt 0 ]; then
        cd_nowe="$(num "$(now_epoch)")"
        if [ "$cd_nowe" -le 0 ] || [ "$cd_nowe" -lt "$last_try_epoch" ]; then
            echo "$cd_interval"
            return 0
        fi
        cd_elapsed=$((cd_nowe-last_try_epoch))
    else
        echo 0
        return 0
    fi
    if [ "$cd_elapsed" -ge "$cd_interval" ]; then echo 0
    else echo $((cd_interval-cd_elapsed)); fi
}

request_reconnect() {
    reason="$1"; nowm="$(num "$(monotime)")"; nowe="$(num "$(now_epoch)")"
    case "$state" in connecting|disconnected) :;; *) decision=not_disconnected; return 0;; esac
    interval="$(retry_interval)"
    case "$reason" in
        manual) [ "$interval" -ge 60 ] || interval=60;;
    esac
    remaining="$(cooldown_remaining "$interval")"
    if [ "$remaining" -gt 0 ]; then
        decision=cooldown
        if [ "$reason" = manual ]; then
            record_event reconnect_skipped "手动恢复未发送：距上次请求尚未达到退避间隔，剩余 $remaining 秒"
        fi
        return 0
    fi
    case "$reason" in auto) auto_enabled || { decision=disabled; return 0; };; esac
    last_try_mono="$nowm"; last_try_epoch="$nowe"; attempts=$((attempts+1))
    retry_streak=$((retry_streak+1)); last_try_reason="$reason"; last_result=pending
    save_state || { decision=state_save_failed; record_event reconnect_skipped "重连状态无法持久化，未发送广播"; return 0; }
    action=com.google.android.intent.action.GCM_RECONNECT
    reply="$(run_bounded 10 "$AM_BIN" broadcast --user 0 -a "$action" -p "$GMS" 2>&1)"
    rc=$?
    if [ "$rc" -eq 0 ] && printf '%s\n' "$reply" | grep -q 'Broadcast completed:' &&
        ! printf '%s\n' "$reply" | grep -qiE 'Permission Denial|SecurityException|not allowed|Error:|result=-[0-9]'; then
        last_result=requested; decision=requested
        completion="$(printf '%s\n' "$reply" | grep 'Broadcast completed:' | tail -n 1)"
        record_event reconnect_requested "$reason UID=0 ${action##*.} exit=$rc $completion；命令返回不代表投递或恢复（第 $attempts 次）"
    else
        last_result=failed; decision=request_failed
        record_event reconnect_failed "$reason UID=0 ${action##*.} exit=$rc；请核对 KernelSU Root 和 GMS 版本"
    fi
    if ! save_state; then
        last_result=pending
        decision=request_result_pending
        record_event reconnect_result_pending "广播结果无法持久化；保留 pending，等待后续 GMS 状态确认"
    fi
}

observe() {
    decision=not_evaluated
    load_state
    nowm="$(num "$(monotime)")"; nowe="$(num "$(now_epoch)")"
    [ "$nowm" -gt 0 ] && [ "$nowe" -gt 0 ] || return 12
    previous_down_state="$state"
    previous_down_observed="${outage_hint_mono:-0}"
    [ "$previous_down_observed" -gt 0 ] || previous_down_observed="$down_observed"
    if [ "$previous_down_observed" -gt "$nowm" ] || [ $((nowm-previous_down_observed)) -gt 900 ]; then previous_down_observed=0; fi
    outage_hint_mono="$previous_down_observed"
    gap_detected=0
    if [ "$sample_epoch" -gt 0 ] && [ "$nowe" -gt "$sample_epoch" ] && [ $((nowe-sample_epoch)) -gt 90 ]; then
        gap_detected=1
        record_event sample_gap "距离上次采样 $((nowe-sample_epoch)) 秒；重新读取内核 TCP 表"
        state=unknown; missing_since=0; missing_epoch=0; conn_since=0; down_observed=0
    fi
    socket="$(scan_sockets)"
    IFS='|' read -r observed new_transport new_remote socket_key socket_reason <<EOF_SOCKET
$socket
EOF_SOCKET
    source=gms_procfs; native_reason=none; parse_detail="$socket_reason"; duration=-1; close_code=
    tcp_hint="$observed"
    case "$observed" in
      connected)
        [ "$previous_down_state" = connected ] || record_event socket_connected "procfs 已观察到 GMS UID 的 MCS ESTABLISHED 连接"
        outage_hint_mono=0
        ;;
      connecting)
        [ "$previous_down_state" = connecting ] || record_event socket_connecting "procfs 已观察到 GMS UID 的 MCS SYN_SENT/SYN_RECV 连接"
        ;;
      absent)
        if [ "$outage_hint_mono" -le 0 ] || [ "$nowm" -lt "$outage_hint_mono" ] || [ $((nowm-outage_hint_mono)) -gt 900 ]; then
            outage_hint_mono="$nowm"
            record_event tcp_absence_started "TCP 与 TCP6 表均完整，未发现 GMS UID 的 5228-5230 连接"
        fi
        observed=disconnected; new_transport=; new_remote=
        ;;
      *)
        observed=unknown; source=procfs_unavailable; native_reason="$socket_reason"
        new_transport=; new_remote=
        record_event sample_unknown "procfs 检测不可用：$socket_reason；不把读取失败当作断联"
        ;;
    esac
    case "$observed" in
      connected)
        if [ "$last_result" = pending ] || { [ "$last_result" = requested ] && [ "$last_try_epoch" -ge "$missing_epoch" ] && [ "$last_try_epoch" -gt 0 ]; }; then
            last_result=restored; last_restore="$nowe"
            record_event reconnect_result_restored "procfs 再次观察到 GMS MCS 已建立连接"
        fi
        if [ "$previous_down_state" = connecting ] || [ "$previous_down_state" = disconnected ] || [ "$previous_down_observed" -gt 0 ]; then
            last_restore="$nowe"; record_event connection_restored "procfs 再次观察到 GMS MCS 已建立连接"
        fi
        current_key="$new_remote"
        if [ "$state" != connected ] || [ "$conn_key" != "$current_key" ] || [ "$conn_since_mono" -le 0 ] ||
           [ "$nowm" -lt "$conn_since_mono" ] || [ "$sample_epoch" -le 0 ] || [ "$nowe" -lt "$sample_epoch" ] ||
           [ $((nowe-sample_epoch)) -gt 45 ]; then conn_since_mono="$nowm"; fi
        conn_key="$current_key"; state=connected; last_seen="$nowe"; missing_since=0; missing_epoch=0
        down_observed=0; retry_streak=0; outage_hint_mono=0; conn_since=0
        ;;
      connecting|disconnected)
        if [ "$state" != connecting ] && [ "$state" != disconnected ]; then
            [ "$previous_down_state" != connected ] || outages=$((outages+1))
            missing_since="$nowm"; missing_epoch="$nowe"; down_observed="$nowm"
            record_event connection_down "procfs 确认 GMS MCS 尚未建立连接：$observed"
        fi
        [ "$down_observed" -gt 0 ] && [ "$down_observed" -le "$nowm" ] || down_observed="$nowm"
        [ "$outage_hint_mono" -gt 0 ] || outage_hint_mono="$nowm"
        state="$observed"; last_down="$missing_epoch"; conn_key=; conn_since=0; conn_since_mono=0
        ;;
      *)
        state=unknown; conn_key=; missing_since=0; missing_epoch=0; conn_since=0; conn_since_mono=0; down_observed=0
        ;;
    esac
    transport="$new_transport"; remote="$new_remote"; sample_epoch="$nowe"
    native_timer_duration=0; native_timer_since=0; native_timer_key=
    save_state || { record_event state_save_failed "FCM 采样状态无法持久化"; return 13; }
    write_evidence || { record_event evidence_save_failed "FCM 采样证据无法持久化"; return 13; }
}

write_evidence() {
    printf '%s|%s|%s|%s|%s|%s\n' "$source" "$duration" "$close_code" "$tcp_hint" "$native_reason" "$decision" > "$EVIDENCE.tmp" && mv -f "$EVIDENCE.tmp" "$EVIDENCE"
}

confirm_recovery() {
    first_state="$state"; first_tcp_hint="$tcp_hint"
    [ "${BERRY_FCM_TEST_MODE:-0}" = 1 ] || sleep 2
    observe || return $?
    if [ "$source" = gms_procfs ] && { { [ "$state" = disconnected ] && [ "$tcp_hint" = absent ]; } || { [ "$state" = connecting ] && [ "$tcp_hint" = connecting ]; }; }; then
        record_event recovery_confirmed "两次独立 procfs 采样确认 GMS MCS 尚未建立连接"
        request_reconnect "$1"
    else
        decision=confirmation_changed
        record_event recovery_confirmation_unavailable "procfs 二次采样未保持断联：first=$first_state/$first_tcp_hint second=$state/$tcp_hint；未发送重连"
    fi
}

auto_recovery() {
    decision=not_disconnected
    [ "$source" = gms_procfs ] || { decision=sample_unavailable; return 0; }
    case "$state" in connecting|disconnected) :;; *) return 0;; esac
    auto_enabled || { decision=disabled; return 0; }
    nowm="$(num "$(monotime)")"; interval="$(retry_interval)"; remaining="$(cooldown_remaining "$interval")"
    if [ "$remaining" -gt 0 ]; then decision=cooldown; return 0; fi
    required=60; [ "$state" = disconnected ] && required=15
    if [ $((nowm-down_observed)) -ge "$required" ]; then confirm_recovery auto
    else decision=waiting_duration; fi
}

status_json() {
    load_state
    nowe="$(num "$(now_epoch)")"; age=-1
    [ "$sample_epoch" -gt 0 ] && [ "$nowe" -ge "$sample_epoch" ] && age=$((nowe-sample_epoch))
    source=unavailable; duration=-1; close_code=; tcp_hint=none; native_reason=unrecognized; decision=not_evaluated
    if [ -f "$EVIDENCE" ]; then IFS='|' read -r source duration close_code tcp_hint native_reason decision < "$EVIDENCE" || :; fi
    case "${decision:-}" in not_evaluated|not_disconnected|sample_unavailable|disabled|cooldown|state_save_failed|requested|request_failed|request_result_pending|waiting_duration|confirmation_changed|) :;; *) decision=not_evaluated;; esac
    case "$native_reason" in none|uid_unavailable|uid_timeout|tcp_unreadable|tcp6_unreadable|tcp_read_error|tcp6_read_error|tcp_empty_result|tcp6_empty_result|tcp_malformed|tcp6_malformed|tcp_truncated|tcp6_truncated|procfs_incomplete|unrecognized) :;; *) native_reason=unrecognized;; esac
    case "$duration" in -1) :;; *) is_uint "$duration" || duration=-1;; esac
    [ "$age" -ge 0 ] && [ "$age" -le 90 ] || state=stale
    # Never present stale duration as live and never extrapolate past the sample.
    connected=0; missing=0
    [ "$state" = connected ] && [ "$duration" -ge 0 ] && connected="$duration"
    { [ "$state" = connecting ] || [ "$state" = disconnected ]; } && [ "$duration" -ge 0 ] && missing="$duration"
    closes=0; successes=0
    if [ -f "$NATIVE_EVENTS" ]; then
        closes="$(awk -F'|' '$2=="gms_close"{n++} END{print n+0}' "$NATIVE_EVENTS")"
        successes="$(awk -F'|' '$2=="gms_connected"{n++} END{print n+0}' "$NATIVE_EVENTS")"
    fi
    auto=0; auto_enabled && auto=1
    interval="$(retry_interval)"
    nowm="$(num "$(monotime)")"
    retry_in="$(cooldown_remaining "$interval")"
    observed_up=-1
    if [ "$state" = connected ] && [ -n "$conn_key" ] &&
       [ "$conn_since_mono" -gt 0 ] && [ "$nowm" -ge "$conn_since_mono" ] &&
       [ "$age" -ge 0 ]; then
        observed_up=$((nowm-conn_since_mono-age))
        [ "$observed_up" -ge 0 ] || observed_up=0
    fi
    observed_seconds=-1
    if { [ "$state" = connecting ] || [ "$state" = disconnected ]; } && [ "$down_observed" -gt 0 ] && [ "$nowm" -ge "$down_observed" ] && [ "$age" -ge 0 ]; then
        observed_seconds=$((nowm-down_observed-age))
        [ "$observed_seconds" -ge 0 ] || observed_seconds=0
    fi
    printf '{"fcm_state":"%s","fcm_source":"%s","fcm_transport":"%s","fcm_remote":"%s","fcm_connection_since":%s,"fcm_connected_seconds":%s,"fcm_disconnect_since":%s,"fcm_missing_seconds":%s,"fcm_native_duration":%s,"fcm_observed_up_seconds":%s,"fcm_native_close_events":%s,"fcm_native_connected_events":%s,"fcm_last_close_code":"%s","fcm_tcp_hint":"%s","fcm_last_seen":%s,"fcm_last_restore":%s,"fcm_last_try":%s,"fcm_last_try_reason":"%s","fcm_last_result":"%s","fcm_attempts":%s,"fcm_outages":%s,"fcm_sample_time":%s,"fcm_sample_age_seconds":%s,"fcm_native_reason":"%s","fcm_auto":%s,"fcm_retry_in":%s,"fcm_retry_streak":%s,"fcm_observed_down_seconds":%s,"fcm_recovery_decision":"%s"}\n' \
      "$state" "$source" "$transport" "$remote" "$conn_since" "$connected" "$last_down" "$missing" "$duration" "$observed_up" "$closes" "$successes" "$close_code" "$tcp_hint" "$last_seen" "$last_restore" "$last_try_epoch" "$last_try_reason" "$last_result" "$attempts" "$outages" "$sample_epoch" "$age" "$native_reason" "$auto" "$retry_in" "$retry_streak" "$observed_seconds" "$decision"
}

run_monitor_command() {
case "${1:-status}" in
    status)
        observe || exit $?
        status_json
        ;;
    snapshot)
        status_json
        ;;
    monitor)
        prior_decision=
        if [ -f "$EVIDENCE" ]; then
            IFS='|' read -r old_source old_duration old_close old_tcp old_reason prior_decision < "$EVIDENCE" || :
        fi
        observe || exit $?
        auto_recovery || exit $?
        write_evidence || { record_event evidence_save_failed "恢复决策证据无法持久化"; exit 13; }
        case "$state" in
          unknown|connecting|disconnected)
            if [ "$decision" != "$prior_decision" ]; then
                record_event recovery_decision "decision=$decision state=$state native_s=$duration reason=$native_reason parser=${parse_detail:-none} retry_streak=$retry_streak"
            fi;;
          connected) :;;
        esac
        ;;
    reconnect)
        observe || exit $?
        if [ "$source" = gms_procfs ] && { [ "$state" = connecting ] || [ "$state" = disconnected ]; }; then
            confirm_recovery manual || exit $?
        else
            decision=not_disconnected
            record_event reconnect_skipped "GMS 已连接或 procfs 证据不完整，未发送手动重连"
        fi
        write_evidence || exit $?
        case "$decision" in
          requested) echo "已请求 GMS 恢复，等待下一次连接采样确认";;
          request_failed) echo "Root 重连广播未确认，请查看恢复事件";;
          request_result_pending) echo "重连命令结果未能持久化，等待后续 GMS 状态确认";;
          state_save_failed) echo "重连状态无法保存，未发送广播";;
          cooldown) echo "仍在重连冷却期";;

          *) echo "GMS 已连接或状态不可确认，未发送重连";;
        esac
        ;;
    events)
        [ -f "$EVENTS" ] && tail -n 80 "$EVENTS" || :
        ;;
    native-events)
        [ -f "$NATIVE_EVENTS" ] && tail -n 80 "$NATIVE_EVENTS" || :
        ;;
    reset-history)
        : > "$EVENTS"; : > "$NATIVE_EVENTS"; echo 'FCM 事件历史已清空（连接状态未更改）'
        ;;
    *) echo 'status|snapshot|monitor|reconnect|events|native-events|reset-history' >&2; exit 2;;
esac
}

with_flock() {
    # Android mksh closes descriptors allocated by exec before launching
    # an external command. Attach the descriptor to this function call.
    lock_error="$("$@" -xn 9 2>&1)"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        if [ -z "$lock_error" ] && [ "$rc" -eq 1 ]; then
            echo "后台正在检查 GMS，请稍后重试" >&2
            return 13
        fi
        printf "无法取得 GMS 检查锁：%s\n" "${lock_error:-退出码 $rc}" >&2
        return 14
    fi
    run_monitor_command "$command"
}

command="${1:-status}"
case "$command" in
    status|monitor|reconnect|reset-history)
        busybox="$KSU_BUSYBOX"
        if [ -x /system/bin/flock ]; then
            with_flock /system/bin/flock 9>>"$DATA/mcs-monitor.lock"
        elif [ -x "$busybox" ] && "$busybox" --list 2>/dev/null | grep -qx flock; then
            with_flock "$busybox" flock 9>>"$DATA/mcs-monitor.lock"
        elif command -v flock >/dev/null 2>&1; then
            with_flock flock 9>>"$DATA/mcs-monitor.lock"
        else
            lock_boot="$(boot_id | tr -cd 'A-Za-z0-9-')"
            [ -n "$lock_boot" ] || exit 11
            lock_dir="$DATA/mcs-monitor-$lock_boot.lockdir"
            if ! mkdir "$lock_dir" 2>/dev/null; then
                echo "后台正在检查 GMS，请稍后重试" >&2
                exit 13
            fi
            trap 'rmdir "$lock_dir" 2>/dev/null || :' EXIT
            trap 'exit 130' HUP INT TERM
            run_monitor_command "$command"
        fi
        ;;
    *) run_monitor_command "$command";;
esac
