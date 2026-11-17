#!/system/bin/sh
# Berry-FCM connection lifecycle monitor (KernelSU/root mode).
# Only observes GMS-owned TCP sockets and requests GCM_RECONNECT; it never
# changes DNS, VPN, proxy, routing, or kills/restarts Google Play services.
set -u

GMS=com.google.android.gms
DATA="${BERRY_FCM_DATA:-/data/adb/berry_fcm_ksu}"
STATE="$DATA/fcm-monitor.state"
EVENTS="$DATA/fcm-events.log"
DNS_IPS="$DATA/mtalk-dns-ips.txt"
LEARNED_IPS="$DATA/mtalk-learned-ips.txt"
DNS_TIME="$DATA/mtalk-dns-time"
SS_BIN="${BERRY_SS_BIN:-ss}"
CMD_BIN="${BERRY_CMD_BIN:-cmd}"
PM_BIN="${BERRY_PM_BIN:-pm}"
DUMPSYS_BIN="${BERRY_DUMPSYS_BIN:-dumpsys}"
AM_BIN="${BERRY_AM_BIN:-am}"
UPTIME_FILE="${BERRY_UPTIME_FILE:-/proc/uptime}"
BOOT_ID_FILE="${BERRY_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}"

if [ "${BERRY_FCM_TEST_MODE:-0}" != 1 ]; then
    [ "$(id -u)" = 0 ] || { echo 'ERROR: 需要 Root / ADB shell 权限' >&2; exit 10; }
fi
mkdir -p "$DATA" || exit 11

is_uint() { case "${1:-}" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
num() { is_uint "${1:-}" && printf '%s\n' "$1" || printf '0\n'; }
now_epoch() { date +%s 2>/dev/null | awk '/^[0-9]+$/ {print; found=1} END {if (!found) print 0}'; }
monotime() { awk '{printf "%d\n", $1}' "$UPTIME_FILE" 2>/dev/null | head -n 1; }
boot_id() { cat "$BOOT_ID_FILE" 2>/dev/null; }

# Persisted fields use a fixed pipe-delimited schema. Never source the file.
load_state() {
    b="$(boot_id)"
    state=never_seen; conn_key=; conn_since=0; conn_since_mono=0; last_seen=0
    missing_since=0; missing_epoch=0; last_try_mono=0; last_try_epoch=0
    attempts=0; last_result=none; last_restore=0; outages=0
    transport=; remote=; sample_epoch=0; last_try_reason=none
    reconnects=0; last_down=0
    prev_b=
    if [ -f "$STATE" ]; then
        IFS='|' read -r prev_b state conn_key conn_since conn_since_mono last_seen missing_since missing_epoch last_try_mono last_try_epoch attempts last_result last_restore outages transport remote sample_epoch last_try_reason reconnects last_down < "$STATE" || :
    fi
    conn_since="$(num "$conn_since")"; conn_since_mono="$(num "$conn_since_mono")"; last_seen="$(num "$last_seen")"
    missing_since="$(num "$missing_since")"; missing_epoch="$(num "$missing_epoch")"
    last_try_mono="$(num "$last_try_mono")"; last_try_epoch="$(num "$last_try_epoch")"
    attempts="$(num "$attempts")"; last_restore="$(num "$last_restore")"
    outages="$(num "$outages")"; sample_epoch="$(num "$sample_epoch")"
    reconnects="$(num "$reconnects")"; last_down="$(num "$last_down")"
    case "$state" in connected|connecting|disconnected|unknown|never_seen) : ;; *) state=unknown;; esac
    case "$last_result" in none|requested|failed|restored) : ;; *) last_result=none;; esac
    case "$last_try_reason" in none|auto|manual) : ;; *) last_try_reason=none;; esac
    if [ -n "$prev_b" ] && [ "$prev_b" != "$b" ]; then
        state=never_seen; conn_key=; conn_since=0; conn_since_mono=0; missing_since=0; missing_epoch=0
        last_try_mono=0; transport=; remote=; sample_epoch=0
        : > "$LEARNED_IPS"
        record_event reboot '设备重启：清除上一启动周期的连接计时，保留历史事件'
    fi
}

save_state() {
    tmp="$STATE.$$"
    printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
      "$b" "$state" "$conn_key" "$conn_since" "$conn_since_mono" "$last_seen" "$missing_since" "$missing_epoch" \
      "$last_try_mono" "$last_try_epoch" "$attempts" "$last_result" "$last_restore" "$outages" \
      "$transport" "$remote" "$sample_epoch" "$last_try_reason" "$reconnects" "$last_down" > "$tmp" && mv -f "$tmp" "$STATE"
}

record_event() {
    e="${1:-event}"; msg="${2:-}"
    msg="$(printf '%s' "$msg" | tr '\r\n|' '   ' | cut -c1-220)"
    printf '%s|%s|%s\n' "$(now_epoch)" "$e" "$msg" >> "$EVENTS"
    lines="$(wc -l < "$EVENTS" 2>/dev/null | tr -d ' ')"; lines="$(num "$lines")"
    if [ "$lines" -gt 120 ]; then tail -n 120 "$EVENTS" > "$EVENTS.tmp" && mv -f "$EVENTS.tmp" "$EVENTS"; fi
}

# Resolve Google's documented mtalk hostnames. These addresses are candidates
# only for GMS UID-owned :443 sockets; arbitrary GMS HTTPS sockets never count.
resolve_host() {
    host="$1"
    if command -v nslookup >/dev/null 2>&1; then
        nslookup "$host" 2>/dev/null | awk '
          /^Name:/ {answer=1; next}
          answer && /Address/ {ip=$NF; if (ip ~ /^[0-9A-Fa-f:.]+$/) print tolower(ip)}'
    elif command -v getent >/dev/null 2>&1; then
        getent ahosts "$host" 2>/dev/null | awk '$1 ~ /^[0-9A-Fa-f:.]+$/ {print tolower($1)}'
    else
        ping -c 1 -W 2 "$host" 2>&1 | sed -n 's/.*(\([0-9A-Fa-f:.]*\)).*/\1/p' | tr 'A-F' 'a-f'
    fi
}

resolve_mtalk() {
    now="$(now_epoch)"; old="$(cat "$DNS_TIME" 2>/dev/null)"; old="$(num "$old")"
    [ -s "$DNS_IPS" ] && [ "$old" -gt 0 ] && [ "$now" -ge "$old" ] && [ $((now-old)) -lt 600 ] && return 0
    tmp="$DNS_IPS.$$"; : > "$tmp"
    for host in mtalk.google.com mtalk4.google.com mtalk-staging.google.com mtalk-dev.google.com \
      alt1-mtalk.google.com alt2-mtalk.google.com alt3-mtalk.google.com alt4-mtalk.google.com \
      alt5-mtalk.google.com alt6-mtalk.google.com alt7-mtalk.google.com alt8-mtalk.google.com; do
        resolve_host "$host" >> "$tmp" 2>/dev/null || :
    done
    if [ -s "$tmp" ]; then sort -u "$tmp" > "$DNS_IPS.new" && mv -f "$DNS_IPS.new" "$DNS_IPS"; fi
    rm -f "$tmp"
    echo "$now" > "$DNS_TIME"
}

normalize_ip() {
    printf '%s' "$1" | tr 'A-F' 'a-f' | sed 's/^\[//;s/\]$//;s/^::ffff://' 
}

gms_uid() {
    uid="$("$CMD_BIN" package list packages -U "$GMS" 2>/dev/null | awk -v p="$GMS" '$1=="package:"p && $2~/^uid:[0-9]+/ {sub(/^uid:/,"",$2); split($2,a,","); if(a[1]~/^[0-9]+$/){print a[1];exit}}')"
    [ -n "$uid" ] || uid="$("$PM_BIN" list packages -U "$GMS" 2>/dev/null | awk -v p="$GMS" '$1=="package:"p && $2~/^uid:[0-9]+/ {sub(/^uid:/,"",$2); split($2,a,","); if(a[1]~/^[0-9]+$/){print a[1];exit}}')"
    if [ -z "$uid" ]; then uid="$("$DUMPSYS_BIN" package "$GMS" 2>/dev/null | sed -n 's/^[[:space:]]*userId=\([0-9][0-9]*\).*/\1/p' | head -n 1)"; fi
    is_uint "$uid" || return 1
    printf '%s\n' "$uid"
}

scan_sockets() {
    uid="$(gms_uid 2>/dev/null)" || { printf 'unknown||||gms_uid_unavailable\n'; return; }
    command -v "$SS_BIN" >/dev/null 2>&1 || { printf 'unknown||||ss_unavailable\n'; return; }
    raw="$("$SS_BIN" -tnpe 2>/dev/null)" || { printf 'unknown||||ss_failed\n'; return; }

    # Learn peer IPs observed on the dedicated MCS ports during this boot.
    printf '%s\n' "$raw" | awk -v u="$uid" '
      ($1=="ESTAB" || $1=="ESTABLISHED") {
        if (!match($0,/uid:[0-9]+/)) next
        owner=substr($0,RSTART+4,RLENGTH-4)+0; if(owner%100000!=u%100000) next
        peer=$5; port=peer; sub(/^.*:/,"",port)
        if(port==5228 || port==5229 || port==5230){ip=peer;sub(/:[0-9]+$/,"",ip);gsub(/^\[|\]$/,"",ip);sub(/^::ffff:/,"",ip);print tolower(ip)}
      }' >> "$LEARNED_IPS"
    [ -s "$LEARNED_IPS" ] && sort -u "$LEARNED_IPS" > "$LEARNED_IPS.tmp" && mv -f "$LEARNED_IPS.tmp" "$LEARNED_IPS"
    ips="$(cat "$DNS_IPS" "$LEARNED_IPS" 2>/dev/null | awk 'NF && !seen[$0]++ {printf "%s%s",sep,$0;sep="|"}')"
    ip_count="$(printf '%s' "$ips" | awk -F'|' '{print NF}')"
    [ -n "$ips" ] || ip_count=0
    printf '%s\n' "$raw" | awk -v u="$uid" -v ips="|$ips|" -v ip_count="$ip_count" '
      function normip(v){sub(/:[0-9]+$/, "", v);gsub(/^\[|\]$/, "", v);sub(/^::ffff:/, "", v);return tolower(v)}
      function known(v){return index(ips,"|" normip(v) "|")>0}
      ($1=="ESTAB" || $1=="ESTABLISHED" || $1=="SYN-SENT" || $1=="SYN_SENT") {
        if (!match($0,/uid:[0-9]+/)) next
        owner=substr($0,RSTART+4,RLENGTH-4)+0; if(owner%100000!=u%100000) next
        peer=$5; port=peer; sub(/^.*:/,"",port); ip=normip(peer); local=$4
        inode=""; if(match($0,/ino:[0-9]+/)) inode=substr($0,RSTART+4,RLENGTH-4)
        key=local ">" peer "#" inode
        established=($1=="ESTAB" || $1=="ESTABLISHED")
        if(established && (port==5228 || port==5229 || port==5230)){
          if(rank<3){rank=3;kind="connected";transport="tcp" port;remote=ip ":" port;bestkey=key}
        } else if(established && port==443 && known(peer)){
          if(rank<2){rank=2;kind="connected";transport="tcp443";remote=ip ":443";bestkey=key}
        } else if(!established && ((port==5228 || port==5229 || port==5230) || (port==443 && known(peer)))){
          if(rank<1){rank=1;kind="connecting";transport="tcp" port;remote=ip ":" port;bestkey=key}
        } else if(established && port==443 && ip_count==0){untrusted=1}
      }
      END {
        if(rank>0) printf "%s|%s|%s|%s|\n",kind,transport,remote,bestkey
        else if(untrusted) print "unknown||||unverified_443_no_mtalk_dns"
        else print "absent||||no_fcm_socket_observed"
      }'
}

is_gms_stopped() {
    line="$("$DUMPSYS_BIN" -t 3 package "$GMS" 2>/dev/null | grep -E '^[[:space:]]*User 0:[[:space:]]+.*installed=' | head -n 1)"
    case " $line " in *' stopped=false '*) echo 0;; *' stopped=true '*) echo 1;; *) echo -1;; esac
}

request_reconnect() {
    reason="$1"; nowm="$(monotime)"; nowe="$(now_epoch)"
    nowm="$(num "$nowm")"; nowe="$(num "$nowe")"
    if [ "$last_try_mono" -gt 0 ] && [ "$nowm" -ge "$last_try_mono" ] && [ $((nowm-last_try_mono)) -lt 300 ]; then return 0; fi
    stopped="$(is_gms_stopped)"
    [ "$stopped" = 0 ] || { record_event reconnect_skipped "GMS stopped 状态为 $stopped，未发送广播"; return 0; }
    last_try_mono="$nowm"; last_try_epoch="$nowe"; attempts=$((attempts+1)); last_try_reason="$reason"; last_result=failed
    save_state
    reply="$("$AM_BIN" broadcast --user 0 -a com.google.android.intent.action.GCM_RECONNECT -p "$GMS" 2>&1)"
    if printf '%s\n' "$reply" | grep -q 'Broadcast completed:'; then
        last_result=requested
        record_event reconnect_requested "$reason 重连请求已发送；广播完成不代表 FCM 已恢复（第 $attempts 次）"
    else
        last_result=failed
        record_event reconnect_failed "$reason 重连广播未确认：$reply"
    fi
    save_state
}

observe() {
    load_state
    nowm="$(num "$(monotime)")"; nowe="$(now_epoch)"; nowe="$(num "$nowe")"
    [ "$nowm" -gt 0 ] && [ "$nowe" -gt 0 ] || { echo 'ERROR: 无法读取系统时间'; return 12; }
    resolve_mtalk
    result="$(scan_sockets)"
    IFS='|' read -r observed new_transport new_remote new_key reason <<EOF_RESULT
$result
EOF_RESULT
    [ -n "$observed" ] || observed=unknown
    case "$observed" in
      connected)
        recovered=0
        if [ "$missing_since" -gt 0 ]; then
            duration=$((nowm-missing_since)); [ "$duration" -ge 0 ] || duration=0
            if [ "$last_seen" -gt 0 ]; then outages=$((outages+1)); fi
            last_restore="$nowe"; recovered=1
            if [ "$last_try_epoch" -ge "$missing_epoch" ] && [ "$last_try_epoch" -gt 0 ]; then last_result=restored; fi
            record_event connection_restored "FCM 连接恢复：$new_transport $new_remote，缺失约 ${duration} 秒"
        elif [ -z "$conn_key" ]; then
            record_event connection_up "首次观察到 FCM 连接：$new_transport $new_remote"
        elif [ "$conn_key" != "$new_key" ]; then
            reconnects=$((reconnects+1))
            record_event socket_replaced "FCM Socket 已更换：$new_transport $new_remote（未观察到明确断联间隔）"
        fi
        if [ "$recovered" = 1 ] || [ "$conn_key" != "$new_key" ] || [ "$conn_since" -eq 0 ]; then conn_since="$nowe"; conn_since_mono="$nowm"; fi
        conn_key="$new_key"; last_seen="$nowe"; missing_since=0; missing_epoch=0
        state=connected; transport="$new_transport"; remote="$new_remote"
        ;;
      connecting)
        if [ "$missing_since" -eq 0 ]; then
            missing_since="$nowm"; missing_epoch="$nowe"; last_down="$nowe"
            [ "$last_seen" -gt 0 ] && record_event connection_down "已建立连接消失，检测到新的连接尝试：$new_remote"
        fi
        state=connecting; transport="$new_transport"; remote="$new_remote"
        ;;
      absent)
        if [ "$missing_since" -eq 0 ]; then
            missing_since="$nowm"; missing_epoch="$nowe"; last_down="$nowe"
            if [ "$last_seen" -gt 0 ]; then
                duration=0; [ "$conn_since" -gt 0 ] && duration=$((last_seen-conn_since))
                record_event connection_down "首次检测到 FCM 连接消失；上次在线 $(date -d "@$last_seen" '+%H:%M:%S' 2>/dev/null || echo "$last_seen")，最近一段连接约 ${duration} 秒"
            else
                record_event connection_not_seen '当前尚未观察到 FCM 长连接，开始计时'
            fi
        fi
        state=disconnected; transport=; remote=
        ;;
      *)
        if [ "$state" != unknown ]; then record_event observation_unknown "FCM 状态无法确认：$reason；本轮不累计断联、不触发自动重连"; fi
        state=unknown; missing_since=0; missing_epoch=0; transport=; remote=
        ;;
    esac
    sample_epoch="$nowe"
    save_state
}

status_json() {
    load_state
    nowm="$(num "$(monotime)")"; nowe="$(now_epoch)"; nowe="$(num "$nowe")"
    missing=0; connected=0; age=-1
    [ "$sample_epoch" -gt 0 ] && [ "$nowe" -ge "$sample_epoch" ] && age=$((nowe-sample_epoch))
    [ "$state" = connected ] && [ "$conn_since_mono" -gt 0 ] && [ "$nowm" -ge "$conn_since_mono" ] && connected=$((nowm-conn_since_mono))
    [ "$missing_since" -gt 0 ] && [ "$nowm" -ge "$missing_since" ] && missing=$((nowm-missing_since))
    display_state="$state"
    [ "$age" -gt 120 ] && display_state=stale
    auto=0; [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ] && auto=1
    printf '{"fcm_state":"%s","fcm_observed_state":"%s","fcm_transport":"%s","fcm_remote":"%s","fcm_connection_since":%s,"fcm_connected_seconds":%s,"fcm_last_seen":%s,"fcm_disconnect_since":%s,"fcm_missing_seconds":%s,"fcm_last_restore":%s,"fcm_last_try":%s,"fcm_last_try_reason":"%s","fcm_last_result":"%s","fcm_attempts":%s,"fcm_outages":%s,"fcm_reconnects":%s,"fcm_sample_time":%s,"fcm_sample_age_seconds":%s,"fcm_auto":%s}\n' \
      "$display_state" "$state" "$transport" "$remote" "$conn_since" "$connected" "$last_seen" "$last_down" "$missing" "$last_restore" "$last_try_epoch" "$last_try_reason" "$last_result" "$attempts" "$outages" "$reconnects" "$sample_epoch" "$age" "$auto"
}

case "${1:-status}" in
    status)
        observe || exit $?
        status_json
        ;;
    monitor)
        observe || exit $?
        if [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ] && { [ "$state" = disconnected ] || [ "$state" = connecting ]; } && [ "$missing_since" -gt 0 ]; then
            nowm="$(num "$(monotime)")"
            if [ "$nowm" -ge "$missing_since" ] && [ $((nowm-missing_since)) -ge 120 ]; then request_reconnect auto; fi
        fi
        ;;
    reconnect)
        load_state
        request_reconnect manual
        ;;
    events)
        [ -f "$EVENTS" ] && tail -n 80 "$EVENTS" || :
        ;;
    reset-history)
        : > "$EVENTS"; echo 'FCM 事件历史已清空（连接状态未更改）'
        ;;
    *) echo 'status|monitor|reconnect|events|reset-history' >&2; exit 2;;
esac
