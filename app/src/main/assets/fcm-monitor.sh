#!/system/bin/sh
# BerryFCM APK: GMS MCS dump is authoritative; TCP is auxiliary evidence.
# Only observes GMS-owned TCP sockets and requests GCM_RECONNECT; it never
# changes DNS, VPN, proxy, routing, or kills/restarts Google Play services.
set -u

GMS=com.google.android.gms
DATA="${BERRY_FCM_DATA:-/data/adb/berry_fcm_ksu}"
STATE="$DATA/mcs-monitor.state"
EVENTS="$DATA/mcs-monitor-events.log"
NATIVE_EVENTS="$DATA/mcs-native-events.log"
EVIDENCE="$DATA/mcs-evidence"
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

if [ "${BERRY_FCM_TEST_MODE:-0}" != 1 ] && [ "${BERRY_FCM_ADB_SHELL:-0}" != 1 ]; then
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

    # Some ADB shells receive redacted ss output with no UID ownership.
    # Never classify that as absence of GMS/FCM: ownership is unobservable.
    if ! printf '%s\n' "$raw" | grep -Eq 'uid:[0-9]+'; then
        printf 'unknown||||ss_uid_metadata_unavailable\n'
        return
    fi

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

# Current state only: do not match historical "Connected" or "Connecting"
# lines. GMS dump may contain private IDs and application data; persist only
# the selected fields and connection lifecycle events.
parse_mcs() {
    awk '
      function sec(v, a,n,i,r) {
        sub(/\/.*/, "", v); n=split(v,a,":")
        if(n<2 || n>3) return -1
        r=0; for(i=1;i<=n;i++){if(a[i]!~/^[0-9]+$/ || (i>1 && a[i]+0>=60))return -1; r=r*60+a[i]}
        return r
      }
      function endpoint(v) {return v ~ /^[A-Za-z0-9.\/:\[\]_-]+$/ ? v : ""}
      {
        sub(/\r$/, ""); line=$0; sub(/^[[:space:]]+/, "",line); sub(/[[:space:]]+$/, "",line)
        if(line~/^DeviceID:/ || line~/^Direct boot mode /){if(header && state!=""){done=1;next} header=1;next}
        if(!header || done) next
        if(line~/^[0-9][0-9]-[0-9][0-9] [0-9][0-9]:/){done=1;next}
        if(line~/^connected=/){
          v=line;sub(/^connected=/,"",v);n=split(v,a,",port=")
          if(n==2 && endpoint(a[1])!="" && a[2]~/^[0-9]+$/ && a[2]+0>0 && a[2]+0<=65535){
            if(state!="" && state!="connected") conflict=1
            state="connected"; remote=a[1] ":" a[2]; port=a[2]
          }
        } else if(line~/^connecting=/){
          v=line;sub(/^connecting=/,"",v)
          if(endpoint(v)!="" && v~/:[0-9]+$/){
            if(state!="" && state!="connecting") conflict=1
            state="connecting"; remote=v; port=v;sub(/^.*:/,"",port)
          }
        } else if(line~/^pending=/){
          if(state!="" && state!="disconnected") conflict=1
          state="disconnected"
          if(line~/,host=/){v=line;sub(/^.*,host=/,"",v);remote=endpoint(v);port=v;sub(/^.*:/,"",port)}
        } else if(line=="Not connected"){
          if(state!="" && state!="disconnected") conflict=1
          state="disconnected"
        } else if(line=="Connecting" && state==""){state="connecting"}
        else if(line=="Disabled"){state="unknown";done=1}
        else if(line~/^connectTime=/){v=line;sub(/^connectTime=/,"",v);ct=sec(v)}
        else if(line~/^disconnectTime=/){v=line;sub(/^disconnectTime=/,"",v);dt=sec(v)}
        else if(line~/^streamId=/){
          n=split(line,a,",")
          for(i=1;i<=n;i++)if(a[i]~/^lastConnectionErrorCode=[A-Za-z0-9_]+$/){
            err=a[i];sub(/^lastConnectionErrorCode=/,"",err)
          }
        }
      }
      END {
        if(!header || conflict || state==""){print "unknown||||-1|";exit}
        if(port!~/^[0-9]+$/ || port+0<1 || port+0>65535)port=""
        duration=-1
        if(state=="connected" && ct!="")duration=ct
        if((state=="connecting" || state=="disconnected") && dt!="")duration=dt
        printf "%s|%s|%s|%s|%d|%s\n",state,(port==""?"":"tcp"port),remote,"gms",duration,err
      }'
}

import_native_events() {
    # Preserve exact event timestamps (device local time, no invented year).
    # A Close event is a socket/MCS close, not necessarily a distinct outage:
    # simultaneous Wi-Fi/cellular and fallback attempts can produce several.
    tmp="$NATIVE_EVENTS.$$"
    { [ ! -f "$NATIVE_EVENTS" ] || cat "$NATIVE_EVENTS"
      printf '%s\n' "$1" | awk '
        {
          sub(/\r$/, ""); sub(/^[[:space:]]+/, "")
          if($0 !~ /^[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]\.[0-9]+ net=-?[0-9]+: /) next
          stamp=$1" "$2; msg=$0;sub(/^[^ ]+ [^ ]+ /,"",msg)
          if(msg~/^net=-?[0-9]+: Close err:[0-9]+([[:space:]]|$)/)kind="gms_close"
          else if(msg~/^net=-?[0-9]+: Connected[[:space:]]*$/)kind="gms_connected"
          else if(msg~/^net=-?[0-9]+: Failed connection err:[0-9]+/)kind="gms_failed"
          else if(msg~/^net=-?[0-9]+: Connecting using McsConnection/ && (msg~/WiFi\(/ || msg~/Cell\(/)) {
            kind="gms_network_attempt"
            msg=(msg~/WiFi\(/ ? "WiFi" : "Cell") " connection attempt; " (msg~/FALLBACK_ALTERNATIVE_HOSTPORT/ ? "fallback" : "primary")
          }
          else if(msg~/^net=-?[0-9]+: Heartbeat alarm delay: [0-9]+ ms$/)kind="gms_heartbeat_delay"
          else next
          gsub(/[|]/," ",msg)
          print stamp "|" kind "|" substr(msg,1,160)
        }'
    } | awk 'NF && !seen[$0]++' | tail -n 600 > "$tmp" && mv -f "$tmp" "$NATIVE_EVENTS"
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
    nowm="$(num "$(monotime)")"; nowe="$(num "$(now_epoch)")"
    [ "$nowm" -gt 0 ] && [ "$nowe" -gt 0 ] || return 12
    if [ "$sample_epoch" -gt 0 ] && [ "$nowe" -gt "$sample_epoch" ] && [ $((nowe-sample_epoch)) -gt 90 ]; then
        record_event sample_gap '采样曾中断；当前状态重新读取 GMS'
        state=unknown; missing_since=0; missing_epoch=0; conn_since=0
    fi
    dump="$("$DUMPSYS_BIN" -t 5 activity service "$GMS/.gcm.GcmService" 2>/dev/null)" || dump=""
    result="$(printf '%s\n' "$dump" | parse_mcs)"
    IFS='|' read -r observed new_transport new_remote source duration close_code <<EOF_RESULT
$result
EOF_RESULT
    tcp_hint=none
    if [ "$observed" = unknown ]; then
        # Optional socket evidence never promotes an unknown native state.
        # No repeated DNS lookups when GMS dumpsys is unavailable.
        socket="$(scan_sockets)"
        tcp_hint="$(printf '%s' "$socket" | cut -d '|' -f 1)"
        case "$tcp_hint" in connected) tcp_hint=established;; connecting|absent|unknown) :;; *) tcp_hint=unknown;; esac
        source=unavailable; duration=-1; close_code=
    else
        import_native_events "$dump"
    fi
    case "$observed" in
      connected)
        if [ "$state" = connecting ] || [ "$state" = disconnected ]; then
            last_restore="$nowe"
            if [ "$last_try_epoch" -ge "$missing_epoch" ] && [ "$last_try_epoch" -gt 0 ]; then last_result=restored; fi
            record_event connection_restored 'GMS 原生状态确认连接成功（不保证每条消息送达）'
        fi
        state=connected; last_seen="$nowe"; missing_since=0; missing_epoch=0
        conn_since=0; conn_since_mono=0
        if [ "$duration" -ge 0 ] && [ "$duration" -le "$nowe" ]; then
            conn_since=$((nowe-duration))
            [ "$duration" -le "$nowm" ] && conn_since_mono=$((nowm-duration))
        fi
        ;;
      connecting|disconnected)
        if [ "$state" != connecting ] && [ "$state" != disconnected ]; then
            missing_since="$nowm"; missing_epoch="$nowe"
            record_event connection_down 'GMS 原生状态确认尚未连接'
        fi
        if [ "$duration" -ge 0 ] && [ "$duration" -le "$nowe" ]; then
            missing_epoch=$((nowe-duration))
            [ "$duration" -lt "$nowm" ] && missing_since=$((nowm-duration))
        fi
        state="$observed"; last_down="$missing_epoch"; conn_since=0; conn_since_mono=0
        ;;
      *)
        state=unknown; missing_since=0; missing_epoch=0; conn_since=0; conn_since_mono=0
        new_transport=; new_remote=
        ;;
    esac
    transport="$new_transport"; remote="$new_remote"; sample_epoch="$nowe"
    printf '%s|%s|%s|%s\n' "$source" "$duration" "$close_code" "$tcp_hint" > "$EVIDENCE.tmp" && mv -f "$EVIDENCE.tmp" "$EVIDENCE"
    save_state
}

status_json() {
    load_state
    nowe="$(num "$(now_epoch)")"; age=-1
    [ "$sample_epoch" -gt 0 ] && [ "$nowe" -ge "$sample_epoch" ] && age=$((nowe-sample_epoch))
    source=unavailable; duration=-1; close_code=; tcp_hint=none
    if [ -f "$EVIDENCE" ]; then IFS='|' read -r source duration close_code tcp_hint < "$EVIDENCE" || :; fi
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
    auto=0; [ "$(cat "$DATA/enabled" 2>/dev/null)" = 1 ] && auto=1
    printf '{"fcm_state":"%s","fcm_source":"%s","fcm_transport":"%s","fcm_remote":"%s","fcm_connection_since":%s,"fcm_connected_seconds":%s,"fcm_disconnect_since":%s,"fcm_missing_seconds":%s,"fcm_native_duration":%s,"fcm_native_close_events":%s,"fcm_native_connected_events":%s,"fcm_last_close_code":"%s","fcm_tcp_hint":"%s","fcm_last_seen":%s,"fcm_last_restore":%s,"fcm_last_try":%s,"fcm_last_result":"%s","fcm_attempts":%s,"fcm_sample_time":%s,"fcm_sample_age_seconds":%s,"fcm_auto":%s}\n' \
      "$state" "$source" "$transport" "$remote" "$conn_since" "$connected" "$last_down" "$missing" "$duration" "$closes" "$successes" "$close_code" "$tcp_hint" "$last_seen" "$last_restore" "$last_try_epoch" "$last_result" "$attempts" "$sample_epoch" "$age" "$auto"
}

case "${1:-status}" in
    status)
        observe || exit $?
        status_json
        ;;
    snapshot)
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
        [ -f "$NATIVE_EVENTS" ] && tail -n 80 "$NATIVE_EVENTS" || :
        ;;
    reset-history)
        : > "$EVENTS"; : > "$NATIVE_EVENTS"; echo 'FCM 事件历史已清空（连接状态未更改）'
        ;;
    *) echo 'status|snapshot|monitor|reconnect|events|reset-history' >&2; exit 2;;
esac
