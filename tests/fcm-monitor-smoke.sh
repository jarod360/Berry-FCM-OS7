#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
MON="${BERRY_MONITOR_SCRIPT:-$ROOT/module/bin/fcm-monitor.sh}"
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
cat > "$TMP/bin/id" <<'MOCK'
#!/bin/sh
if [ "$*" = "-u" ]; then
    echo "${MCS_UID:-0}"
else /usr/bin/id "$@"; fi
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
printf "%s\n" "DeviceID: [fixture]" "connected=mtalk.google.com/142.250.157.188,port=5228" "connectTime=03:22/00:00" > "$MCS_FIXTURE"
j="$(run status)"; assert_json "$j" fcm_state=connected; assert_json "$j" fcm_native_duration=202
printf '%s\n' 'DeviceID: [fixture]' 'connected=mtalk.google.com/[2001:db8::1],port=443' 'connectTime=00:03/00:00' > "$MCS_FIXTURE"
j="$(run status)"; assert_json "$j" fcm_state=connected; assert_json "$j" 'fcm_remote=mtalk.google.com/[2001:db8::1]:443'
run reconnect; requests 0

printf "%s\n" "Permission Denial" > "$MCS_FIXTURE"
j="$(run status)"; assert_json "$j" fcm_state=unknown; assert_json "$j" fcm_tcp_hint=established
run monitor; run reconnect; requests 0
echo "PASS: native state is authoritative; TCP alone is auxiliary"

settime 10030
printf "%s\n" "DeviceID: [fixture]" "Not connected" > "$MCS_FIXTURE"
run monitor; requests 0
settime 10060
export MCS_NEXT_FIXTURE="$TMP/next"
printf "%s\n" "DeviceID: [fixture]" "connected=mtalk.google.com/142.250.157.188,port=5228" "connectTime=00:01/00:00" > "$MCS_NEXT_FIXTURE"
run monitor; requests 0
j="$(run snapshot)"; assert_json "$j" fcm_recovery_decision=confirmation_changed
unset MCS_NEXT_FIXTURE

# A fresh native timer after resume permits a double check in the same window.
settime 10360
printf "%s\n" "DeviceID: [fixture]" "Not connected" "disconnectTime=02:00/00:00" > "$MCS_FIXTURE"
run monitor; requests 1
test "$(tail -n 1 "$MCS_AM_LOG")" = "broadcast --user 0 -a com.google.android.intent.action.GCM_RECONNECT -p com.google.android.gms"
j="$(run snapshot)"; assert_json "$j" fcm_recovery_decision=requested; assert_json "$j" fcm_state=disconnected
run events | grep -q "|sample_gap|"
settime 10390; run reconnect; requests 1
settime 10660; run monitor; requests 2
settime 10960; run monitor; requests 2
settime 11260; run monitor; requests 3
printf "%s\n" "DeviceID: [fixture]" "connected=mtalk.google.com/142.250.157.188,port=5228" "connectTime=00:01/00:00" > "$MCS_FIXTURE"
settime 11290
j="$(run status)"; assert_json "$j" fcm_last_result=restored; assert_json "$j" fcm_retry_streak=0
echo "PASS: suspend gap, double confirmation, UID 0 broadcast, shared backoff and native restoration"

settime 11600
printf "%s\n" "DeviceID: [fixture]" "Not connected" "disconnectTime=01:00/00:00" > "$MCS_FIXTURE"
export MCS_STOPPED=true; run monitor; requests 3
unset MCS_STOPPED
echo 0 > "$BERRY_FCM_DATA/enabled"; run monitor; requests 3
echo 1 > "$BERRY_FCM_DATA/enabled"
export MCS_UID=2000
if run reconnect; then echo "non-root request unexpectedly accepted" >&2; exit 1; fi
unset MCS_UID
requests 3
export MCS_AM_FAIL=1; run monitor; requests 4
j="$(run snapshot)"; assert_json "$j" fcm_recovery_decision=request_failed
unset MCS_AM_FAIL
echo "PASS: stopped GMS, opt-out, non-root rejection and failed-broadcast classification"

(
    flock -xn 9
    if run reconnect; then echo "concurrent request unexpectedly acquired lock" >&2; exit 1; fi
) 9>"$BERRY_FCM_DATA/mcs-monitor.lock"
requests 4
echo "PASS: daemon and WebUI share a single lock"
# A manager-provided BusyBox can exist while omitting the flock applet.
# With no system flock on PATH, the same monitor must still read GMS.
mkdir -p "$TMP/compatible"
for bin in awk cat cp cut grep head mkdir mv rmdir tail tr wc; do
    ln -s "$(command -v "$bin")" "$TMP/compatible/$bin"
done
cat > "$TMP/bin/busybox-minimal" <<'MOCK'
#!/bin/sh
[ "$1" = --list ] && printf '%s\n' awk mkdir
MOCK
chmod +x "$TMP/bin/busybox-minimal"
export BERRY_KSU_BUSYBOX="$TMP/bin/busybox-minimal"
printf '%s\n' 'DeviceID: [fixture]' 'connected=mtalk.google.com/142.250.157.188,port=5228' 'connectTime=00:30/00:00' > "$MCS_FIXTURE"
settime 11630
j="$(PATH="$TMP/bin:$TMP/compatible" /bin/sh "$MON" status)"
assert_json "$j" fcm_state=connected
[ ! -e "$BERRY_FCM_DATA/mcs-monitor-test-boot.lockdir" ]
echo "PASS: native reading works with a KernelSU BusyBox lacking flock"
