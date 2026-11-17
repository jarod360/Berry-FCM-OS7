#!/system/bin/sh
# Berry-FCM KSU backend. Root-only, no Shizuku, no system partitions touched.
set -u
MODDIR="$(cd "$(dirname "$0")/.." && pwd)"
DATA=/data/adb/berry_fcm_ksu
TARGETS="$DATA/targets.txt"
ENABLED="$DATA/enabled"
GMS=com.google.android.gms
[ "$(id -u)" = 0 ] || { echo 'ERROR: KernelSU Root 未授权 (uid != 0)' >&2; exit 10; }
mkdir -p "$DATA" || exit 11
[ -f "$TARGETS" ] || printf '%s\n' "$GMS" > "$TARGETS"
[ -f "$ENABLED" ] || echo 0 > "$ENABLED"

valid_pkg() { printf '%s\n' "$1" | grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'; }
target_list() {
    printf '%s\n' "$GMS"
    while IFS= read -r p || [ -n "$p" ]; do
        [ "$p" = "$GMS" ] && continue
        valid_pkg "$p" && printf '%s\n' "$p"
    done < "$TARGETS"
}
installed() {
    cmd package path --user 0 "$1" 2>/dev/null | grep -q '^package:' && return 0
    pm path --user 0 "$1" 2>/dev/null | grep -q '^package:' && return 0
    dumpsys -t 3 package "$1" 2>/dev/null | grep -q 'Package \['
}
# 1=stopped, 0=not stopped, -1=no reliable data
stopped() {
    line="$(dumpsys -t 3 package "$1" 2>/dev/null | grep -E '^[[:space:]]*User 0:[[:space:]]+.*installed=' | head -n 1)"
    [ -n "$line" ] || { echo -1; return; }
    case " $line " in
        *' stopped=true '*) echo 1 ;;
        *' stopped=false '*) echo 0 ;;
        *) echo -1 ;;
    esac
}
persistent() {
    # Modern GMS builds may run MCS inside com.google.android.gms, without a
    # separate .persistent process. Do not mislabel that as a missing process.
    pidof "$GMS" >/dev/null 2>&1 && { echo 1; return; }
    echo 0
}
# Only observe established sockets owned by the GMS app UID (including
# secondary Android user IDs such as 99910200). HTTPS/443 is deliberately
# NOT counted: other GMS services also use 443, so it is not proof of FCM.
fcm_socket() {
    uid="$(gms_uid)"
    case "$uid" in ''|*[!0-9]*) echo -1; return ;; esac
    sockets="$(ss -tnpe 2>/dev/null)" || { echo -1; return; }
    printf '%s\n' "$sockets" | awk -v u="$uid" '
      $1=="ESTAB" || $1=="ESTABLISHED" {
        if (!match($0,/uid:[0-9]+/)) next
        owner=substr($0,RSTART+4,RLENGTH-4)+0
        if (owner%100000 != u%100000) next
        dst=$5; sub(/^.*:/,"",dst)
        if (dst==5228 || dst==5229 || dst==5230) found=1
      }
      END {print found?1:0}
    '
}
mcs() { fcm_socket; }

# The Linux monotonic uptime avoids wall-clock jumps; boot_id avoids carrying
# elapsed time across device restarts. State is never sourced as shell code.
STATE="$DATA/fcm.state"
boot_id() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }
monotime() { awk '{print int($1)}' /proc/uptime; }
num() { case "$1" in ''|*[!0-9]*) echo 0 ;; *) echo "$1" ;; esac; }
load_fcm_state() {
    b="$(boot_id)"; missing=0; last_try=0; last_seen=0; tries=0; last_result=none; last_try_epoch=0
    if [ -f "$STATE" ]; then
        IFS='|' read -r prev_b missing last_try last_seen tries last_result last_try_epoch < "$STATE" || :
        if [ "$prev_b" != "$b" ]; then
            missing=0; last_try=0; last_seen=0; tries=0; last_result=none; last_try_epoch=0
        fi
    fi
    missing="$(num "$missing")"; last_try="$(num "$last_try")"
    last_seen="$(num "$last_seen")"; tries="$(num "$tries")"
    last_try_epoch="$(num "$last_try_epoch")"
    case "$last_result" in requested|failed|none) : ;; *) last_result=none ;; esac
}
save_fcm_state() {
    tmp="$STATE.$$"
    printf '%s|%s|%s|%s|%s|%s|%s\n' "$b" "$missing" "$last_try" "$last_seen" "$tries" "$last_result" "$last_try_epoch" > "$tmp" && mv -f "$tmp" "$STATE"
}
fcm_monitor() {
    load_fcm_state
    now="$(monotime)"; now="$(num "$now")"
    status="$(fcm_socket)"
    if [ "$status" = 1 ]; then
        if [ "$missing" -gt 0 ]; then echo "FCM 端口连接已恢复（之前未观察到 $((now - missing)) 秒）"; fi
        missing=0; last_seen="$(date +%s)"
        save_fcm_state
        return 0
    fi
    # Unknown socket permissions/UID are not equivalent to disconnection.
    if [ "$status" != 0 ]; then
        if [ "$missing" -gt 0 ]; then
            missing=0; save_fcm_state
            echo 'FCM 连接检测异常，清除连续缺失计时，不误触发重连'
        fi
        return 0
    fi
    if [ "$missing" -eq 0 ] || [ "$now" -lt "$missing" ]; then
        missing="$now"; echo '未观察到 GMS FCM 端口连接，开始计时'
    fi
    offline=$((now - missing))
    save_fcm_state
    [ "$offline" -ge 120 ] || return 0
    [ "$last_try" -eq 0 ] || [ $((now - last_try)) -ge 300 ] || return 0
    [ "$(stopped "$GMS")" = 0 ] || { echo 'GMS 已停止或状态未知，不发送重连广播'; return 0; }
    request_reconnect "$offline"
}
request_reconnect() {
    offline="$1"
    # Record the request before broadcasting, preventing immediate retry loops.
    now="$(monotime)"; now="$(num "$now")"
    last_try="$now"; last_try_epoch="$(date +%s)"; tries=$((tries + 1)); last_result=failed; save_fcm_state
    reply="$(am broadcast --user 0 -a com.google.android.intent.action.GCM_RECONNECT -p "$GMS" 2>&1)"
    if printf '%s\n' "$reply" | grep -q 'Broadcast completed:'; then
        last_result=requested
        echo "已请求 GMS 重新连接 FCM（缺失 ${offline} 秒，第 ${tries} 次，广播结果不代表已连接）"
    else
        echo "FCM 重连广播未确认（缺失 ${offline} 秒）：$reply"
    fi
    save_fcm_state
}
manual_reconnect() {
    load_fcm_state
    request_reconnect 0
}
# Extended checks inspired by fcmfix-coloros17. Pure Android public shell commands;
# vendor-specific ColorOS hooks are NOT present or implied.
gms_uid() {
    # Android 17 varies the `list packages -U` output, so try package dump as fallback.
    uid="$(cmd package list packages -U "$GMS" 2>/dev/null |         awk -v pkg="$GMS" '$1=="package:"pkg && $2~/^uid:[0-9]+/ {sub(/^uid:/,"",$2); split($2,ids,","); if (ids[1]~/^[0-9]+$/) {print ids[1]; exit}}')"
    if [ -z "$uid" ]; then
        uid="$(pm list packages -U "$GMS" 2>/dev/null |             awk -v pkg="$GMS" '$1=="package:"pkg && $2~/^uid:[0-9]+/ {sub(/^uid:/,"",$2); split($2,ids,","); if (ids[1]~/^[0-9]+$/) {print ids[1]; exit}}')"
    fi
    if [ -z "$uid" ]; then
        uid="$(dumpsys package "$GMS" 2>/dev/null | sed -n 's/^[[:space:]]*userId=\([0-9][0-9]*\).*/\1/p' | head -n 1)"
    fi
    case "$uid" in ''|*[!0-9]*) return 1;; *) printf '%s\n' "$uid" ;; esac
}
dozeraw() { cmd deviceidle whitelist 2>/dev/null; }
doze_state() {
    o="$(dozeraw)" || { echo -1; return; }
    [ -n "$o" ] || { echo -1; return; }
    printf '%s\n' "$o" | grep -qE '^(system|user),com\.google\.android\.gms(,|$)' && echo 1 || echo 0
}
net_list() { cmd netpolicy list restrict-background-whitelist 2>/dev/null; }
net_state() {
    uid="$(gms_uid)"
    case "$uid" in ''|*[!0-9]*) echo -1; return ;; esac
    o="$(net_list)" || { echo -1; return; }
    [ -n "$o" ] || { echo -1; return; }
    printf '%s\n' "$o" | grep -Eq "(^|[^0-9])${uid}([^0-9]|$)" && echo 1 || echo 0
}
net_saver() {
    o="$(cmd netpolicy get restrict-background 2>/dev/null)" || { echo -1; return; }
    case "$o" in *enabled*) echo 1;; *disabled*) echo 0;; *) echo -1;; esac
}
notification_state() {
    o="$(cmd appops get "$GMS" POST_NOTIFICATION 2>/dev/null)" || { echo -1; return; }
    case "$o" in
       *POST_NOTIFICATION*allow*) echo 1;;
       *POST_NOTIFICATION*ignore*|*POST_NOTIFICATION*deny*|*POST_NOTIFICATION*errored*) echo 0;;
       *) echo -1;;
    esac
}
# Return -1 for unknown, 1 if flagged, 0 if explicitly false.
user_flag() {
    l="$(dumpsys -t 3 package "$1" 2>/dev/null | grep -E '^[[:space:]]*User 0:[[:space:]]+.*installed=' | head -n 1)"
    [ -n "$l" ] || { echo -1; return; }
    case " $l " in *" $2=true "*) echo 1;; *" $2=false "*) echo 0;; *) echo -1;; esac
}
policy() {
    kind="${1:-}"; op="${2:-}"
    case "$kind/$op" in
      doze/add)
        [ "$(doze_state)" = 1 ] && { echo 'GMS 已在 Doze 白名单，无需修改'; return 0; }
        cmd deviceidle whitelist "+$GMS" || return 1
        [ "$(doze_state)" = 1 ] || { echo '加入失败：复检未通过' >&2; return 1; }
        : > "$DATA/doze_added"; echo '已加入 GMS Doze 白名单（仅标准 Android Doze）'
        ;;
      doze/undo)
        [ -f "$DATA/doze_added" ] || { echo '不是模块添加的白名单，拒绝撤销' >&2; return 2; }
        cmd deviceidle whitelist "-$GMS" || return 1
        [ "$(doze_state)" = 0 ] || { echo '撤销失败：复检未通过' >&2; return 1; }
        rm -f "$DATA/doze_added"; echo '已撤销模块添加的 Doze 白名单'
        ;;
      net/add)
        uid="$(gms_uid)"
        case "$uid" in ''|*[!0-9]*) echo '无法取得 GMS UID' >&2; return 2;; esac
        [ "$(net_state)" = 1 ] && { echo 'GMS 已被允许使用后台计量网络，无需修改'; return 0; }
        cmd netpolicy add restrict-background-whitelist "$uid" || return 1
        [ "$(net_state)" = 1 ] || { echo '加入失败：复检未通过' >&2; return 1; }
        printf '%s\n' "$uid" > "$DATA/net_added_uid"
        echo "已放行 GMS UID $uid（仅 Android 数据节省模式）"
        ;;
      net/undo)
        uid="$(gms_uid)"; saved="$(cat "$DATA/net_added_uid" 2>/dev/null)"
        [ -n "$uid" ] && [ "$uid" = "$saved" ] || { echo 'UID 不一致或非本模块添加，拒绝撤销' >&2; return 2; }
        cmd netpolicy remove restrict-background-whitelist "$uid" || return 1
        [ "$(net_state)" = 0 ] || { echo '撤销失败：复检未通过' >&2; return 1; }
        rm -f "$DATA/net_added_uid"; echo '已撤销模块添加的后台数据放行'
        ;;
      *) echo 'policy doze|net add|undo' >&2; return 2;;
    esac
}
check() {
    g=0; installed "$GMS" && g=1
    s=-1; [ "$g" = 1 ] && s="$(stopped "$GMS")"
    probe=0; [ "$s" != -1 ] && probe=1
    proc="$(persistent)"; mc="$(mcs)"
    load_fcm_state
    age=-1
    now="$(monotime)"; now="$(num "$now")"
    [ "$missing" -gt 0 ] && [ "$now" -ge "$missing" ] && age=$((now - missing))
    [ "$mc" = 1 ] && age=0
    dz="$(doze_state)"; ns="$(net_saver)"; na="$(net_state)"; nt="$(notification_state)"
    dz_owned=0; [ -f "$DATA/doze_added" ] && dz_owned=1
    na_owned=0; [ -f "$DATA/net_added_uid" ] && [ "$(cat "$DATA/net_added_uid")" = "$(gms_uid)" ] && na_owned=1
    recv="$(dumpsys -t 2 activity broadcasts 2>/dev/null | grep -c 'ReceiverList{.*com\.google\.android\.gms/' || true)"
    case "$recv" in ''|*[!0-9]*) recv=0 ;; esac
    items=''; comma=''
    for pkg in $(target_list); do
        [ "$pkg" = "$GMS" ] && continue
        ins=0; st=-1
        suspended=-1; hidden=-1
        if installed "$pkg"; then
            ins=1; st="$(stopped "$pkg")"
            suspended="$(user_flag "$pkg" suspended)"
            hidden="$(user_flag "$pkg" hidden)"
        fi
        items="${items}${comma}{\"pkg\":\"${pkg}\",\"installed\":${ins},\"stopped\":${st},\"suspended\":${suspended},\"hidden\":${hidden}}"
        comma=','
    done
    auto="$(cat "$ENABLED" 2>/dev/null)"; [ "$auto" = 1 ] || auto=0
    printf '{"probe":%s,"gms_installed":%s,"gms_stopped":%s,"gcm_persistent":%s,"mcs":%s,"receivers":%s,"guard":%s,"doze":%s,"doze_owned":%s,"data_saver":%s,"net_allowed":%s,"net_owned":%s,"notification":%s,"fcm_missing_seconds":%s,"fcm_last_seen":%s,"fcm_last_try":%s,"fcm_attempts":%s,"fcm_last_result":"%s","targets":[%s]}\n' "$probe" "$g" "$s" "$proc" "$mc" "$recv" "$auto" "$dz" "$dz_owned" "$ns" "$na" "$na_owned" "$nt" "$age" "$last_seen" "$last_try_epoch" "$tries" "$last_result" "$items"
}
heartbeat() {
    ok=0
    for spec in \
       '-a com.google.android.intent.action.MCS_HEARTBEAT -p com.google.android.gms' \
       '-a com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW -p com.google.android.gms' \
       '-a com.google.android.gcm.intent.SEND -n com.google.android.gms/.gcm.GcmSenderProxy'; do
        # Only hardcoded strings above are expanded into arguments.
        # shellcheck disable=SC2086
        out="$(am broadcast --user 0 $spec 2>&1)"
        if printf '%s\n' "$out" | grep -q 'Broadcast completed: result=0'; then
            echo "心跳成功: $spec"
        else
            echo "心跳未确认: $spec ($out)"
            ok=1
        fi
    done
    return "$ok"
}
fix() {
    changed=0; failed=0; count=0
    for pkg in $(target_list); do
        installed "$pkg" || { echo "$pkg 未安装，跳过"; continue; }
        st="$(stopped "$pkg")"
        [ "$st" = 0 ] && { echo "$pkg 未停止"; continue; }
        [ "$st" = -1 ] && { echo "$pkg stopped 状态未知，跳过"; failed=$((failed + 1)); continue; }
        [ "$count" -lt 10 ] || { echo '本轮最多修复 10 个应用'; break; }
        count=$((count + 1))
        if cmd package unstop --user 0 "$pkg" >/dev/null 2>&1 && [ "$(stopped "$pkg")" = 0 ]; then
            echo "$pkg 已解除 stopped (复检通过)"
            changed=$((changed + 1))
        else
            echo "$pkg 解除 stopped 失败 (可能权限/系统限制)"
            failed=$((failed + 1))
        fi
    done
    if installed "$GMS" && { [ "${HEARTBEAT_MODE:-manual}" != auto ] || [ "$changed" -gt 0 ]; }; then
        heartbeat || echo '部分心跳未确认，并不代表推送必然失败'
    fi
    echo "本轮解除 stopped: $changed, 失败: $failed"
    [ "$failed" = 0 ]
}
watch() {
    # 90-second window, every 15 seconds including both endpoints.
    n=0
    while [ "$n" -lt 7 ]; do
        echo "巡检 $((n + 1))/7: $(date '+%H:%M:%S')"
        HEARTBEAT_MODE=auto fix
        n=$((n + 1))
        [ "$n" -lt 7 ] && sleep 15
    done
}
# Read-only socket reachability. A success only proves the device's CURRENT
# system route can connect; VPN, TUN and upstream transparent proxies may apply.
network_test() {
    echo '== FCM TCP 连通性（只读） =='
    echo '注意：测试进程为 Root UID 0，与 GMS 的 VPN/路由规则可能不同；TCP 成功不能代表 GMS 已直连。'
    echo '验证无代理：关闭手机 VPN，并确认 Wi-Fi 路由器未代理后再测；移动网络另测。'
    if ! /system/bin/toybox nc --help 2>&1 | grep -q -- '-z'; then
        echo '系统 toybox nc 不支持 -z 扫描，无法进行 TCP 测试。'
        return 2
    fi
    failures=0
    for port in 5228 5229 5230 443; do
        if /system/bin/toybox nc -4 -z -w 3 mtalk.google.com "$port" </dev/null >/dev/null 2>&1; then
            echo "mtalk.google.com:$port  TCP 可建立"
        else
            echo "mtalk.google.com:$port  未能建立（可能 DNS、路由、拦截或超时）"
            failures=$((failures+1))
        fi
    done
    if /system/bin/toybox nc -4 -z -w 3 alt1-mtalk.google.com 5228 </dev/null >/dev/null 2>&1; then
        echo 'alt1-mtalk.google.com:5228  TCP 可建立'
    else
        echo 'alt1-mtalk.google.com:5228  未能建立（可能 DNS、路由、拦截或超时）'
        failures=$((failures+1))
    fi
    echo '仅测试 TCP 握手；并不等于 GMS 已认证、FCM 已注册或消息可送达。'
    echo "未建立项: $failures/5（未建立不等于永久不可达）"
}
link() {
    case "${1:-}" in
      autostart)
        am start -n com.vivo.permissionmanager/.activity.BgStartUpManagerActivity >/dev/null 2>&1 || \
        am start -a com.iqoo.secure.BGSTARTUPMANAGER
        ;;
      gmsnotif)
        am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE "$GMS"
        ;;
      gcmdiag)
        # Google Play services' internal diagnostics Activity. Availability depends on GMS build.
        am start -n com.google.android.gms/.gcm.GcmDiagnostics
        ;;
      appdetails)
        p="${2:-}"; valid_pkg "$p" || { echo '应用包名不合法' >&2; return 2; }
        am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$p"
        ;;
      *) echo 'invalid link'; return 1 ;;
    esac
}
case "${1:-}" in
    check) check ;;
    netdiag) network_test ;;
    fcm-monitor) fcm_monitor ;;
    reconnect) manual_reconnect ;;
    fix) fix ;;
    watch) watch ;;
    targets)
        case "${2:-}" in
            list) target_list ;;
            add)
                p="${3:-}"; valid_pkg "$p" || { echo '包名不合法'; exit 2; }
                if ! target_list | grep -qxF "$p"; then printf '%s\n' "$p" >> "$TARGETS"; fi
                target_list ;;
            remove)
                p="${3:-}"; valid_pkg "$p" && [ "$p" != "$GMS" ] || { echo '不可移除 GMS / 包名错误'; exit 2; }
                tmp="$TARGETS.tmp.$$"; grep -vxF "$p" "$TARGETS" > "$tmp" || true
                mv -f "$tmp" "$TARGETS"; target_list ;;
            reset) printf '%s\n' "$GMS" > "$TARGETS"; target_list ;;
            *) echo 'targets list|add|remove|reset' >&2; exit 2 ;;
        esac
        ;;
    guard)
        case "${2:-}" in
            on)
                echo 1 > "$ENABLED"
                # Service may be absent until next boot; launch after a user enables guard.
                sh "$MODDIR/service.sh" </dev/null >/dev/null 2>&1 &
                echo '自动守护已开启（30 秒检测 FCM，连续约 2 分钟未观察到连接则请求重连，至少间隔 5 分钟）'
                ;;
            off) echo 0 > "$ENABLED"; load_fcm_state; missing=0; save_fcm_state; echo '自动守护已关闭' ;;
            status) cat "$ENABLED" ;;
            *) echo 'guard on|off|status' >&2; exit 2 ;;
        esac
        ;;
    policy) policy "${2:-}" "${3:-}" ;;
    links) link "${2:-}" "${3:-}" ;;
    log) [ -f "$DATA/guard.log" ] && tail -n 100 "$DATA/guard.log" || echo '暂无守护日志' ;;
    *) echo 'check|netdiag|fcm-monitor|reconnect|fix|watch|targets|guard|policy|links|log' >&2; exit 2 ;;
esac
