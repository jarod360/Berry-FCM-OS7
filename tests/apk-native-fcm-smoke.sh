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
  *"activity service"*)
    if [ -n "${MCS_NEXT_FIXTURE:-}" ] && [ -f "$MCS_NEXT_FIXTURE" ]; then
      cat "$MCS_FIXTURE"
      mv "$MCS_NEXT_FIXTURE" "$MCS_FIXTURE"
    else cat "$MCS_FIXTURE"; fi;;
  *"package"*) echo "    User 0: ceDataInode=1 installed=true stopped=${MCS_STOPPED:-false} suspended=false hidden=false";;
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
if [ "${MCS_AM_FAIL:-0}" = 1 ]; then
    echo 'Permission Denial: fixture'
    echo 'Broadcast completed: result=0'
    exit 1
fi
echo 'Broadcast completed: result=0'
# Model a reconnect that actually leads to a new native connected sample.
if [ -n "${MCS_AM_CONNECTED_FIXTURE:-}" ]; then
    cp "$MCS_AM_CONNECTED_FIXTURE" "$MCS_FIXTURE"
fi
MOCK
cat > "$TMP/bin/date" <<'MOCK'
#!/bin/sh
if [ "$*" = '+%s' ]; then cat "$MCS_CLOCK"; else /bin/date "$@"; fi
MOCK
chmod +x "$TMP/bin/"*
echo 10000 > "$TMP/uptime"
echo test-boot > "$TMP/bootid"
echo 1 > "$TMP/data/enabled"
: > "$TMP/am.log"
export PATH="$TMP/bin:/usr/bin:/bin"
export BERRY_FCM_TEST_MODE=1 BERRY_FCM_DATA="$TMP/data"
export BERRY_UPTIME_FILE="$TMP/uptime" BERRY_BOOT_ID_FILE="$TMP/bootid"
export MCS_FIXTURE="$TMP/dump" MCS_AM_LOG="$TMP/am.log" MCS_CLOCK="$TMP/epoch"
export BERRY_FCM_AUTO=1 BERRY_AUTO_GATE=ready
settime() { printf '%s\n' "$1" > "$TMP/uptime"; printf '%s\n' "$((1700000000+$1))" > "$MCS_CLOCK"; }
settime 10000
run() { sh "$MON" "$1"; }
requests() { [ "$(wc -l < "$MCS_AM_LOG" | tr -d ' ')" = "$1" ]; }
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
    11-18 06:46:15.399 net=1: Connecting using McsConnection{GcmNetwork{114 WiFi(1)}, type=1}
    11-18 06:54:26.201 net=0: Connecting using McsConnection{GcmNetwork{118 Cell(0)}, type=0, FALLBACK_ALTERNATIVE_HOSTPORT}
    11-18 06:59:52.003 net=1: Heartbeat alarm delay: 143273 ms
EOF
j="$(run status)"
assert_json "$j" fcm_state=connecting
assert_json "$j" fcm_native_duration=3315
assert_json "$j" fcm_native_close_events=3
# A long previous outage must not trigger on the first observation.
run monitor; requests 0
settime 10015
export BERRY_AUTO_GATE=settling
run monitor; requests 0
settime 10045
export BERRY_AUTO_GATE=offline
run monitor; run reconnect; requests 0
export BERRY_AUTO_GATE=ready BERRY_FCM_AUTO=0
run monitor; requests 0
export BERRY_FCM_AUTO=1
run monitor; requests 1
run monitor; requests 1
j="$(run snapshot)"; assert_json "$j" fcm_last_result=requested
assert_json "$j" fcm_retry_streak=1; assert_json "$j" fcm_retry_in=300
# Confirm a long native outage twice in the same awake window after a gap.
settime 10345
run monitor; requests 2
run recovery-events | grep -q 'recovery_confirmed'
j="$(run snapshot)"; assert_json "$j" fcm_retry_in=600
settime 10360
run monitor; requests 2
settime 10660
run monitor; requests 2
settime 10675
run monitor; requests 2
settime 10944
run monitor; requests 2
settime 10945
run monitor; requests 2
settime 10960
run monitor; requests 3
j="$(run snapshot)"; assert_json "$j" fcm_retry_in=900
test "$(grep -c 'GCM_RECONNECT' "$MCS_AM_LOG")" = 3
! grep -q "MCS_HEARTBEAT" "$MCS_AM_LOG"
run events | grep -q 'gms_network_attempt|Cell connection attempt; fallback'
run events | grep -q 'gms_heartbeat_delay|'
run recovery-events | grep -q 'reconnect_requested'
# Confirm native recovery, never the broadcast result alone.
settime 10976
cat > "$MCS_FIXTURE" <<'EOF'
    DeviceID: [fixture]
    connected=mtalk.google.com/142.250.157.188,port=5228
    connectTime=00:03/00:00
EOF
j="$(run status)"
assert_json "$j" fcm_state=connected
assert_json "$j" fcm_last_result=restored
assert_json "$j" fcm_retry_streak=0
run reconnect; requests 3
settime 11050
cat > "$MCS_FIXTURE" <<'EOF'
    DeviceID: [fixture]
    pending=Wed Nov 18 06:46:15 CST 2026,host=mtalk.google.com:5228
    disconnectTime=01:01:02/00:00
EOF
j="$(run status)"
assert_json "$j" fcm_state=disconnected
assert_json "$j" fcm_native_duration=3662
export MCS_AM_FAIL=1
run reconnect; requests 4
j="$(run snapshot)"; assert_json "$j" fcm_last_result=failed
run recovery-events | grep -q 'reconnect_failed'
run reconnect; requests 4
unset MCS_AM_FAIL
printf '%s\n' 'Permission Denial' '11-18 06:25:43.757 net=1: Connected' > "$MCS_FIXTURE"
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_tcp_hint=established
assert_json "$j" fcm_native_duration=-1
assert_json "$j" fcm_native_close_events=3
assert_json "$j" fcm_native_reason=permission_denied
run monitor; run reconnect; requests 4
# Partial dumps are not valid, including when a current-looking field preceded a timeout.
printf '%s\n' 'DeviceID: [fixture]' 'connected=mtalk.google.com/142.250.157.188,port=5228' 'DUMP TIMEOUT' > "$MCS_FIXTURE"
j="$(run status)"; assert_json "$j" fcm_state=unknown; assert_json "$j" fcm_native_reason=timeout
printf '%s\n' 'No services match' > "$MCS_FIXTURE"
export MCS_STOPPED=true
j="$(run status)"; assert_json "$j" fcm_native_reason=gms_stopped
run monitor; requests 4
# Newer GMS may emit the same exact state and timing fields without the
# legacy DeviceID heading. Accept the corroborated native structure.
unset MCS_STOPPED
cat > "$MCS_FIXTURE" <<'EOF'
SERVICE com.google.android.gms/.gcm.GcmService abcd pid=1234
  Client:
    connected=mtalk.google.com/142.250.157.188,port=5228
    connectTime=03:22/00:00
    11-18 04:59:52.453 net=1: Close err:28 time:597
EOF
j="$(run status)"
assert_json "$j" fcm_state=connected
assert_json "$j" fcm_native_duration=202
# State-looking text by itself, without a header or companion field, is not evidence.
printf '%s\n' 'connected=mtalk.google.com/142.250.157.188,port=5228' > "$MCS_FIXTURE"
j="$(run status)"
assert_json "$j" fcm_state=unknown
# Native fields in a conflicting state must not certify an online verdict.
printf '%s\n' 'connected=mtalk.google.com/142.250.157.188,port=5228' 'disconnectTime=55:15/00:00' > "$MCS_FIXTURE"
j="$(run status)"
assert_json "$j" fcm_state=unknown
printf '%s\n' 'connecting=alt7-mtalk.google.com:5228' 'disconnectTime=55:15/00:00' > "$MCS_FIXTURE"
j="$(run status)"
assert_json "$j" fcm_state=connecting
assert_json "$j" fcm_native_duration=3315
# A line that resembles a status but occurs after timestamped history cannot
# send FCM to the online state.
printf '%s\n' '11-18 06:25:43.757 net=1: Connected' 'connected=mtalk.google.com/142.250.157.188,port=5228' 'connectTime=03:22/00:00' > "$MCS_FIXTURE"
j="$(run status)"
assert_json "$j" fcm_state=unknown
# Recovery on the second native check must cancel the broadcast.
settime 13000
printf '%s\n' 'connecting=alt7-mtalk.google.com:5228' 'disconnectTime=55:15/00:00' > "$MCS_FIXTURE"
export MCS_NEXT_FIXTURE="$TMP/next-dump"
printf '%s\n' 'connected=mtalk.google.com/142.250.157.188,port=5228' 'connectTime=00:10/00:00' > "$MCS_NEXT_FIXTURE"
run monitor; requests 4
j="$(run snapshot)"; assert_json "$j" fcm_state=connected
unset MCS_NEXT_FIXTURE
echo 'PASS: MCS state, outage duration, deduplication, network gate, opt-out, retry cooldown, same-window confirmation, targeted reconnect, permission and timeout handling'

# Screenshot regression: authoritative Not connected with no disconnectTime.
# New state directory: third-party Shell must follow the same recovery rules.
export BERRY_FCM_ADB_SHELL=1
rm -f "$BERRY_FCM_DATA"/mcs-monitor.state "$BERRY_FCM_DATA"/mcs-evidence "$BERRY_FCM_DATA"/mcs-monitor-events.log
: > "$MCS_AM_LOG"
settime 14000
printf '%s\n' 'DeviceID: [fixture]' 'Not connected' > "$MCS_FIXTURE"
run monitor; requests 0
j="$(run snapshot)"; assert_json "$j" fcm_native_duration=-1
assert_json "$j" fcm_recovery_decision=waiting_duration
settime 14015
run monitor; requests 1
j="$(run snapshot)"; assert_json "$j" fcm_last_result=requested
assert_json "$j" fcm_recovery_decision=requested
assert_json "$j" fcm_observed_down_seconds=15
# System sleeps beyond the 90s limit; restoration still must be detected.
settime 14120
printf '%s\n' 'DeviceID: [fixture]' 'connected=mtalk.google.com/142.250.157.188,port=5228' 'connectTime=00:02/00:00' > "$MCS_FIXTURE"
run monitor; requests 1
j="$(run snapshot)"; assert_json "$j" fcm_state=connected
assert_json "$j" fcm_last_result=restored
assert_json "$j" fcm_last_restore=1700014120
run recovery-events | grep -q 'connection_restored'
# Two untimed disconnect reads on opposite sides of a suspend gap.
settime 14320
printf '%s\n' 'DeviceID: [fixture]' 'Not connected' > "$MCS_FIXTURE"
run monitor; requests 1
settime 14440
run monitor; requests 2
j="$(run snapshot)"; assert_json "$j" fcm_recovery_decision=requested
# A newly observed online result during confirmation cancels GCM_RECONNECT.
settime 14740
export MCS_NEXT_FIXTURE="$TMP/next-dump"
printf '%s\n' 'DeviceID: [fixture]' 'connected=mtalk.google.com/142.250.157.188,port=5228' 'connectTime=00:02/00:00' > "$MCS_NEXT_FIXTURE"
run monitor; requests 2
j="$(run snapshot)"; assert_json "$j" fcm_state=connected
assert_json "$j" fcm_recovery_decision=confirmation_changed
unset MCS_NEXT_FIXTURE
# Network fallback can repeatedly reset a short native disconnect timer;
# uninterrupted observations still make recovery eligible.
settime 15040
printf '%s\n' 'DeviceID: [fixture]' 'connecting=mtalk.google.com:5228' 'disconnectTime=00:05/00:00' > "$MCS_FIXTURE"
run monitor; requests 2
settime 15100
run monitor; requests 2
settime 15160
run monitor; requests 3
j="$(run snapshot)"; assert_json "$j" fcm_recovery_decision=requested
# Explicit stopped state must not be overridden, even with down evidence.
settime 15460
export MCS_STOPPED=true
run monitor; requests 3
j="$(run snapshot)"; assert_json "$j" fcm_recovery_decision=gms_stopped_unknown
unset MCS_STOPPED
echo 'PASS: untimed FCM down, third-party shell, suspend restoration, reconnect timer reset, interrupted confirmation and stopped GMS'

# Root-mode regression: exactly the command proved effective on the device.
# Do not infer success from am exit/result alone: require the next native read.
unset BERRY_FCM_ADB_SHELL
rm -f "$BERRY_FCM_DATA"/mcs-monitor.state "$BERRY_FCM_DATA"/mcs-evidence "$BERRY_FCM_DATA"/mcs-monitor-events.log
: > "$MCS_AM_LOG"
printf '%s\n' 'DeviceID: [fixture]' 'Not connected' > "$MCS_FIXTURE"
export MCS_AM_CONNECTED_FIXTURE="$TMP/root-connected"
printf '%s\n' 'DeviceID: [fixture]' 'connected=mtalk.google.com/108.177.98.188,port=5228' 'connectTime=00:01/00:00' > "$MCS_AM_CONNECTED_FIXTURE"
settime 16000
run monitor; requests 0
settime 16015
run monitor; requests 1
test "$(cat "$MCS_AM_LOG")" = 'broadcast --user 0 -a com.google.android.intent.action.GCM_RECONNECT -p com.google.android.gms'
j="$(run snapshot)"
assert_json "$j" fcm_last_result=requested
run recovery-events | grep -q 'auto UID=.*GCM_RECONNECT exit=0 Broadcast completed: result=0'
settime 16030
run monitor; requests 1
j="$(run snapshot)"
assert_json "$j" fcm_state=connected
assert_json "$j" fcm_last_result=restored
run recovery-events | grep -q 'connection_restored'
unset MCS_AM_CONNECTED_FIXTURE
# A manual attempt inside the one-minute rate limit must leave an explicit skip
# record rather than claim that it sent the command.
settime 16040
printf '%s\n' 'DeviceID: [fixture]' 'Not connected' > "$MCS_FIXTURE"
run reconnect; requests 1
j="$(run snapshot)"
assert_json "$j" fcm_recovery_decision=cooldown
run recovery-events | grep -q '手动恢复未发送'
echo 'PASS: Root automatic recovery command and native recovery confirmation, explicit manual cooldown'
