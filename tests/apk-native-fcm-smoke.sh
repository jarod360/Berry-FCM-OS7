#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
MON="$ROOT/app/src/main/assets/fcm-monitor.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/bin" "$TMP/data"
cat > "$TMP/bin/dumpsys" <<'MOCK'
#!/bin/sh
case "$*" in
  *"activity service"*) cat "$MCS_FIXTURE";;
  *"package"*) echo '    User 0: ceDataInode=1 installed=true stopped=false suspended=false hidden=false';;
esac
MOCK
cat > "$TMP/bin/cmd" <<'MOCK'
#!/bin/sh
echo 'package:com.google.android.gms uid:10200'
MOCK
cat > "$TMP/bin/ss" <<'MOCK'
#!/bin/sh
echo 'ESTAB 0 0 192.168.1.8:45000 142.250.157.188:5228 uid:10200 ino:101'
MOCK
cat > "$TMP/bin/am" <<'MOCK'
#!/bin/sh
echo "$*" >> "$MCS_AM_LOG"
echo 'Broadcast completed: result=0'
MOCK
chmod +x "$TMP/bin/"*
echo 10000 > "$TMP/uptime"
echo test-boot > "$TMP/bootid"
echo 1 > "$TMP/data/enabled"
: > "$TMP/am.log"
export PATH="$TMP/bin:/usr/bin:/bin"
export BERRY_FCM_TEST_MODE=1 BERRY_FCM_DATA="$TMP/data"
export BERRY_UPTIME_FILE="$TMP/uptime" BERRY_BOOT_ID_FILE="$TMP/bootid"
export MCS_FIXTURE="$TMP/dump" MCS_AM_LOG="$TMP/am.log"
run() { sh "$MON" "$1"; }
assert_json() {
    python3 - "$1" "$2" <<'PY'
import json,sys
j=json.loads(sys.argv[1]); k,v=sys.argv[2].split('=',1)
assert str(j[k])==v,(k,j[k],v,j)
PY
}
cat > "$MCS_FIXTURE" <<'EOF'
    DeviceID: [fixture]
    connected=mtalk.google.com/142.250.157.188,port=5228
    streamId=1/2,connects=8,connectAttempts=9,lastConnectionErrorCode=ERR_IO_RST_HB
    connectTime=03:22/00:00
    11-18 06:25:41.008 net=1: Close err:28 time:346
    11-18 06:25:43.757 net=1: Connected
    11-18 06:29:52.357 net=1: Close err:28 time:248
    11-18 06:29:54.332 net=1: Connected
EOF
j="$(run status)"
assert_json "$j" fcm_state=connected
assert_json "$j" fcm_source=gms
assert_json "$j" fcm_native_duration=202
assert_json "$j" fcm_native_close_events=2
assert_json "$j" fcm_last_close_code=ERR_IO_RST_HB
j="$(run status)"; assert_json "$j" fcm_native_close_events=2
cat > "$MCS_FIXTURE" <<'EOF'
    DeviceID: [fixture]
    Connecting
    connecting=alt7-mtalk.google.com:5228
    disconnectTime=55:15/00:00
    11-18 06:29:54.332 net=1: Connected
    11-18 06:46:15.398 net=1: Close err:28 time:387
EOF
j="$(run status)"
assert_json "$j" fcm_state=connecting
assert_json "$j" fcm_native_duration=3315
assert_json "$j" fcm_native_close_events=3
run monitor
[ "$(wc -l < "$MCS_AM_LOG" | tr -d ' ')" = 1 ]
run monitor
[ "$(wc -l < "$MCS_AM_LOG" | tr -d ' ')" = 1 ]
cat > "$MCS_FIXTURE" <<'EOF'
    DeviceID: [fixture]
    pending=Wed Nov 18 06:46:15 CST 2026,host=mtalk.google.com:5228
    disconnectTime=01:01:02/00:00
EOF
j="$(run status)"
assert_json "$j" fcm_state=disconnected
assert_json "$j" fcm_native_duration=3662
printf '%s\n' 'Permission Denial' '11-18 06:25:43.757 net=1: Connected' > "$MCS_FIXTURE"
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_tcp_hint=established
assert_json "$j" fcm_native_duration=-1
assert_json "$j" fcm_native_close_events=3
run monitor
[ "$(wc -l < "$MCS_AM_LOG" | tr -d ' ')" = 1 ]
echo 'PASS: native MCS evidence, duration, history deduplication, conservative reconnect, TCP fallback'
