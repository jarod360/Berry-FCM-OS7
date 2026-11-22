#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
MON="${BERRY_MONITOR_SCRIPT:-$ROOT/module/bin/fcm-monitor.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/bin" "$TMP/data" "$TMP/proc"
cat > "$TMP/bin/date" <<'MOCK'
#!/bin/sh
if [ "$#" -eq 1 ] && [ "$1" = +%s ]; then cat "$MCS_CLOCK"; else exec /bin/date "$@"; fi
MOCK
cat > "$TMP/bin/id" <<'MOCK'
#!/bin/sh
[ "$*" = "-u" ] && { echo "${MCS_UID:-0}"; exit 0; }
exec /usr/bin/id "$@"
MOCK
cat > "$TMP/bin/cmd" <<'MOCK'
#!/bin/sh
[ "${MCS_UID_MODE:-ok}" = missing ] || echo 'package:com.google.android.gms uid:10200'
MOCK
cat > "$TMP/bin/pm" <<'MOCK'
#!/bin/sh
[ "${MCS_UID_MODE:-ok}" = missing ] || echo 'package:com.google.android.gms uid:10200'
MOCK
cat > "$TMP/bin/timeout" <<'MOCK'
#!/bin/sh
if [ "${MCS_AM_TIMEOUT:-0}" = 1 ] && [ "$2" = am ]; then
    shift
    echo "$*" >> "$MCS_AM_LOG"
    exit 124
fi
if [ "${MCS_UID_MODE:-ok}" = timeout_both ] ||
   { [ "${MCS_UID_MODE:-ok}" = timeout_cmd ] && [ "$2" = cmd ]; } ||
   { [ "${MCS_UID_MODE:-ok}" = partial_timeout_cmd ] && [ "$2" = cmd ]; }; then
    if [ "${MCS_UID_MODE:-ok}" = partial_timeout_cmd ] && [ "$2" = cmd ]; then
        echo 'package:com.google.android.gms uid:99999'
    fi
    exit 124
fi
shift
exec "$@"
MOCK
cat > "$TMP/bin/am" <<'MOCK'
#!/bin/sh
echo "$*" >> "$MCS_AM_LOG"
if [ "${MCS_AM_FAIL:-0}" = 1 ]; then
    echo 'Permission Denial: fixture'
    echo 'Broadcast completed: result=0'
    exit 1
fi
if [ "${MCS_FAIL_STATE_MV:-0}" = 1 ]; then : > "$MCS_FAIL_STATE_MV_MARKER"; fi
echo 'Broadcast completed: result=0'
MOCK
cat > "$TMP/bin/mv" <<'MOCK'
#!/bin/sh
dest=
for arg do dest="$arg"; done
case "$dest" in
  */mcs-monitor.state)
    if [ -n "${MCS_STATE_MV_COUNT_FILE:-}" ]; then
      count=0
      [ ! -f "$MCS_STATE_MV_COUNT_FILE" ] || count="$(cat "$MCS_STATE_MV_COUNT_FILE")"
      count=$((count+1))
      echo "$count" > "$MCS_STATE_MV_COUNT_FILE"
      if [ -n "${MCS_FAIL_STATE_MV_AT:-}" ] && [ "$count" -eq "$MCS_FAIL_STATE_MV_AT" ]; then exit 1; fi
    fi
    if [ -n "${MCS_FAIL_STATE_MV_MARKER:-}" ] && [ -f "$MCS_FAIL_STATE_MV_MARKER" ]; then exit 1; fi
    ;;
esac
exec /bin/mv "$@"
MOCK
cat > "$TMP/bin/flock" <<'MOCK'
#!/bin/sh
exec /usr/bin/flock "$@"
MOCK
chmod +x "$TMP/bin/"*
export PATH="$TMP/bin:/usr/bin:/bin"
export BERRY_FCM_TEST_MODE=1 BERRY_FCM_DATA="$TMP/data"
export BERRY_UPTIME_FILE="$TMP/uptime" BERRY_BOOT_ID_FILE="$TMP/bootid"
export BERRY_PROC_NET_TCP="$TMP/proc/tcp" BERRY_PROC_NET_TCP6="$TMP/proc/tcp6"
export MCS_CLOCK="$TMP/epoch" MCS_AM_LOG="$TMP/am.log"
echo 10000 > "$TMP/uptime"
echo test-boot > "$TMP/bootid"
echo 1 > "$TMP/data/enabled"
: > "$MCS_AM_LOG"
settime() { echo "$1" > "$TMP/uptime"; echo "$((1700000000+$1))" > "$MCS_CLOCK"; }
setboot() { echo "$1" > "$TMP/uptime"; echo "$((1700000000+$2))" > "$MCS_CLOCK"; }
proc_absent() { header "$BERRY_PROC_NET_TCP"; header "$BERRY_PROC_NET_TCP6"; }
proc_connected() { proc_absent; tcp_row "$BERRY_PROC_NET_TCP" 5228 01; }
header() {
    printf '%s\n' '  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode' > "$1"
}
tcp_row() {
    printf '%s\n' "  0: 0100007F:9C40 9C00008E:$(printf '%04X' "$2")  $3 00000000:00000000 00:00000000 0000000A 10200 0 12345" >> "$1"
}
assert_json() {
    python3 - "$1" "$2" <<'PY'
import json,sys
j=json.loads(sys.argv[1]); k,v=sys.argv[2].split('=',1)
assert str(j[k])==v,(k,j[k],v,j)
PY
}
run() { sh "$MON" "$1"; }
requests() { [ "$(wc -l < "$MCS_AM_LOG" | tr -d ' ')" = "$1" ]; }
settime 10000
header "$BERRY_PROC_NET_TCP"
tcp_row "$BERRY_PROC_NET_TCP" 5228 01
header "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=connected
assert_json "$j" fcm_source=gms_procfs
assert_json "$j" fcm_tcp_hint=connected
requests 0
echo 'PASS: IPv4 GMS UID ESTABLISHED MCS socket is connected'

# A timed-out cmd lookup falls back to pm; two timed-out lookups remain UNKNOWN.
export MCS_UID_MODE=timeout_cmd
j="$(run status)"
assert_json "$j" fcm_state=connected
unset MCS_UID_MODE
echo 'PASS: timed-out cmd UID lookup falls back to pm'

# Partial UID output from a timed-out command must be discarded before fallback.
export MCS_UID_MODE=partial_timeout_cmd
j="$(run status)"
assert_json "$j" fcm_state=connected
unset MCS_UID_MODE
echo 'PASS: partial timeout output cannot override complete PM UID'

export MCS_UID_MODE=timeout_both
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_native_reason=uid_timeout
run monitor
requests 0
unset MCS_UID_MODE
echo 'PASS: bounded UID lookup timeout cannot trigger recovery'

# Positive evidence remains usable when the other address-family table is unavailable.
rm -f "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=connected
requests 0
echo 'PASS: a valid IPv4 match is sufficient despite unavailable TCP6'

# A TCP SYN_SENT socket is connecting, not established or absent.
header "$BERRY_PROC_NET_TCP"
tcp_row "$BERRY_PROC_NET_TCP" 5229 02
j="$(run status)"
assert_json "$j" fcm_state=connecting
assert_json "$j" fcm_tcp_hint=connecting
requests 0
echo 'PASS: SYN_SENT is classified as connecting'

# A matching socket in tcp6 is positive evidence even with no IPv4 socket.
header "$BERRY_PROC_NET_TCP"
header "$BERRY_PROC_NET_TCP6"
printf '%s\n' '  0: 00000000000000000000000001000000:9C40 0000000000000000000000008E00009C:146E 01 00000000:00000000 00:00000000 00000000 10200 0 23456' >> "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=connected
echo 'PASS: IPv6 GMS UID MCS socket is detected'

# A valid positive match wins over an incomplete second table.
header "$BERRY_PROC_NET_TCP"
tcp_row "$BERRY_PROC_NET_TCP" 5228 01
printf '%s\n' 'malformed row' > "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=connected
requests 0
echo 'PASS: valid socket evidence is not discarded by the other table'

# An ordinary HTTPS socket is not an MCS connection, even when owned by GMS.
header "$BERRY_PROC_NET_TCP"
tcp_row "$BERRY_PROC_NET_TCP" 443 01
header "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=disconnected
requests 0
echo 'PASS: GMS HTTPS 443 socket is not mistaken for MCS'

# A different UID's MCS socket must not count as GMS.
header "$BERRY_PROC_NET_TCP"
printf '%s\n' '  0: 0100007F:9C40 9C00008E:146C 01 00000000:00000000 00:00000000 00000000 1000 0 12345' >> "$BERRY_PROC_NET_TCP"
header "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=disconnected
assert_json "$j" fcm_source=gms_procfs
assert_json "$j" fcm_tcp_hint=absent
echo 'PASS: complete tables with another UID only prove GMS socket absence'

# Continuous complete absence is confirmed twice and triggers one targeted request.
settime 10015
run monitor
requests 1
grep -q 'GCM_RECONNECT' "$MCS_AM_LOG"
j="$(run snapshot)"
assert_json "$j" fcm_state=disconnected
assert_json "$j" fcm_recovery_decision=requested
echo 'PASS: procfs-only absence reaches confirmed reconnect'

# Malformed or unreadable tables are unknown, never negative evidence.
header "$BERRY_PROC_NET_TCP"
echo 'malformed row' >> "$BERRY_PROC_NET_TCP"
header "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_source=procfs_unavailable
assert_json "$j" fcm_native_reason=tcp_malformed
run monitor
requests 1
echo 'PASS: malformed procfs input reports the exact source failure'

# Unknown TCP state values are malformed, not silently treated as no socket.
header "$BERRY_PROC_NET_TCP"
tcp_row "$BERRY_PROC_NET_TCP" 5228 FF
header "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_native_reason=tcp_malformed
run monitor
requests 1
echo 'PASS: invalid TCP state cannot become negative evidence'

# Oversized rows are truncated, not accepted as a complete negative sample.
header "$BERRY_PROC_NET_TCP"
awk 'BEGIN { printf "%1100s\n", "x" }' >> "$BERRY_PROC_NET_TCP"
header "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_native_reason=tcp_truncated
run monitor
requests 1
echo 'PASS: oversized procfs rows cannot prove absence'

# A missing TCP6 source prevents negative evidence, but not a valid TCP positive match.
header "$BERRY_PROC_NET_TCP"
tcp_row "$BERRY_PROC_NET_TCP" 5228 01
rm -f "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=connected
requests 1
echo 'PASS: positive TCP evidence survives missing TCP6'

# Without a positive match, a missing TCP6 source is UNKNOWN.
header "$BERRY_PROC_NET_TCP"
rm -f "$BERRY_PROC_NET_TCP6"
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_native_reason=tcp6_unreadable
run monitor
requests 1
echo 'PASS: missing TCP6 table cannot be treated as absence'

header "$BERRY_PROC_NET_TCP6"
export MCS_UID_MODE=missing
j="$(run status)"
assert_json "$j" fcm_state=unknown
assert_json "$j" fcm_native_reason=uid_unavailable
run monitor
requests 1
unset MCS_UID_MODE
echo 'PASS: unavailable GMS UID cannot be treated as absence'

# State persistence failure before broadcast must block the reconnect request.
export BERRY_FCM_DATA="$TMP/pre-request-write-fail-data"
mkdir -p "$BERRY_FCM_DATA"
echo 1 > "$BERRY_FCM_DATA/enabled"
: > "$MCS_AM_LOG"
proc_absent
settime 29500
run status
: > "$TMP/state-mv-count"
export MCS_STATE_MV_COUNT_FILE="$TMP/state-mv-count" MCS_FAIL_STATE_MV_AT=3
settime 29515
run monitor
requests 0
test "$(cat "$TMP/state-mv-count")" = 3
test "$(awk -F '|' '{print $9}' "$BERRY_FCM_DATA/mcs-monitor.state")" = 0
test "$(awk -F '|' '{print $10}' "$BERRY_FCM_DATA/mcs-monitor.state")" = 0
test "$(awk -F '|' '{print $11}' "$BERRY_FCM_DATA/mcs-monitor.state")" = 0
test "$(awk -F '|' '{print $12}' "$BERRY_FCM_DATA/mcs-monitor.state")" = none
grep -q '|reconnect_skipped|重连状态无法持久化，未发送广播' "$BERRY_FCM_DATA/mcs-monitor-events.log"
assert_json "$(run snapshot)" fcm_recovery_decision=state_save_failed
unset MCS_STATE_MV_COUNT_FILE MCS_FAIL_STATE_MV_AT
echo 'PASS: pending-state write failure blocks broadcast'

# If persisting the broadcast result fails, pending survives reboot until a fresh socket appears.
export BERRY_FCM_DATA="$TMP/result-pending-data"
mkdir -p "$BERRY_FCM_DATA"
echo 1 > "$BERRY_FCM_DATA/enabled"
: > "$MCS_AM_LOG"
proc_absent
settime 31000
run status
export MCS_FAIL_STATE_MV=1 MCS_FAIL_STATE_MV_MARKER="$TMP/fail-state-mv"
rm -f "$MCS_FAIL_STATE_MV_MARKER"
settime 31015
run monitor
requests 1
test "$(awk -F '|' '{print $11}' "$BERRY_FCM_DATA/mcs-monitor.state")" = 1
test "$(awk -F '|' '{print $12}' "$BERRY_FCM_DATA/mcs-monitor.state")" = pending
test "$(awk -F '|' '{print $22}' "$BERRY_FCM_DATA/mcs-monitor.state")" = 1
assert_json "$(run snapshot)" fcm_last_result=pending
rm -f "$TMP/fail-state-mv"
unset MCS_FAIL_STATE_MV
echo pending-reboot > "$TMP/bootid"
setboot 1 31016
run monitor
requests 1
test "$(awk -F '|' '{print $9}' "$BERRY_FCM_DATA/mcs-monitor.state")" = -1
j="$(run snapshot)"
assert_json "$j" fcm_last_result=pending
assert_json "$j" fcm_retry_in=59
proc_connected
setboot 2 31017
j="$(run status)"
assert_json "$j" fcm_last_result=restored
assert_json "$j" fcm_retry_streak=0
test "$(awk -F '|' '{print $13}' "$BERRY_FCM_DATA/mcs-monitor.state")" -gt 0
grep -q '|reconnect_result_restored|procfs 再次观察到 GMS MCS 已建立连接' "$BERRY_FCM_DATA/mcs-monitor-events.log"
echo 'PASS: pending request survives reboot and resolves on new procfs evidence'

# Reboot retains retry streak and remaining monotonic/epoch cooldown.
export BERRY_FCM_DATA="$TMP/reboot-cooldown-data"
mkdir -p "$BERRY_FCM_DATA"
echo 1 > "$BERRY_FCM_DATA/enabled"
: > "$MCS_AM_LOG"
proc_absent
settime 33000
run status
settime 33015
run monitor
requests 1
awk -F '|' 'NF != 27 { exit 1 } END { if (NR != 1) exit 1 }' "$BERRY_FCM_DATA/mcs-monitor.state"
test "$(awk -F '|' '{print $10}' "$BERRY_FCM_DATA/mcs-monitor.state")" -gt 0
test "$(awk -F '|' '{print $22}' "$BERRY_FCM_DATA/mcs-monitor.state")" = 1
echo boot-two > "$TMP/bootid"
setboot 1 33016
run monitor
requests 1
j="$(run snapshot)"; assert_json "$j" fcm_retry_streak=1; assert_json "$j" fcm_attempts=1; assert_json "$j" fcm_retry_in=59
setboot 20 33035
run monitor
requests 1
j="$(run snapshot)"; assert_json "$j" fcm_retry_streak=1; assert_json "$j" fcm_retry_in=40
echo 1699990000 > "$MCS_CLOCK"
echo 21 > "$TMP/uptime"
run monitor
requests 1
j="$(run snapshot)"; assert_json "$j" fcm_retry_streak=1; assert_json "$j" fcm_retry_in=39
setboot 60 33075
run monitor
requests 2
j="$(run snapshot)"; assert_json "$j" fcm_retry_streak=2; assert_json "$j" fcm_attempts=2
echo 'PASS: reboot and wall-clock rollback preserve retry cooldown'

# Manual reconnect retains its independent 60-second cooldown after reboot.
export BERRY_FCM_DATA="$TMP/manual-reboot-data"
mkdir -p "$BERRY_FCM_DATA"
echo 1 > "$BERRY_FCM_DATA/enabled"
: > "$MCS_AM_LOG"
proc_absent
settime 34000
run reconnect
requests 1
echo manual-reboot-two > "$TMP/bootid"
setboot 1 34001
run reconnect
requests 1
j="$(run snapshot)"; assert_json "$j" fcm_last_try_reason=manual; assert_json "$j" fcm_retry_in=59
echo 'PASS: manual reconnect cooldown survives reboot'

# Broadcast failures are recorded, not reported as successful requests.
export BERRY_FCM_DATA="$TMP/broadcast-fail-data"
mkdir -p "$BERRY_FCM_DATA"
echo 1 > "$BERRY_FCM_DATA/enabled"
: > "$MCS_AM_LOG"
proc_absent
settime 36000
run status
settime 36015
export MCS_AM_FAIL=1
run monitor
requests 1
j="$(run snapshot)"
assert_json "$j" fcm_recovery_decision=request_failed
assert_json "$j" fcm_last_result=failed
unset MCS_AM_FAIL
echo 'PASS: failed broadcast is classified and persisted'

# A timed-out reconnect broadcast is bounded and persisted as failed.
export BERRY_FCM_DATA="$TMP/broadcast-timeout-data"
mkdir -p "$BERRY_FCM_DATA"
echo 1 > "$BERRY_FCM_DATA/enabled"
: > "$MCS_AM_LOG"
proc_absent
settime 37000
run status
settime 37015
export MCS_AM_TIMEOUT=1
run monitor
requests 1
j="$(run snapshot)"
assert_json "$j" fcm_recovery_decision=request_failed
assert_json "$j" fcm_last_result=failed
unset MCS_AM_TIMEOUT
echo 'PASS: timed-out broadcast cannot stall patrol and is recorded as failed'

# The same flock protects daemon and manual monitor entry points.
(
    /usr/bin/flock -xn 9
    if run reconnect; then
        echo 'concurrent reconnect unexpectedly acquired monitor lock' >&2
        exit 1
    fi
) 9>"$BERRY_FCM_DATA/mcs-monitor.lock"
requests 1
echo 'PASS: concurrent monitor invocation is rejected by the shared lock'

# Non-root execution is rejected before any monitoring or broadcast work.
export BERRY_FCM_DATA="$TMP/nonroot-data"
mkdir -p "$BERRY_FCM_DATA"
export MCS_UID=2000
if run status >/dev/null 2>&1; then
    echo 'non-root monitor unexpectedly accepted' >&2
    exit 1
fi
unset MCS_UID
echo 'PASS: non-root invocation is rejected'

# A minimal manager BusyBox without flock must use the mkdir lock fallback.
mkdir -p "$TMP/compatible"
for bin in awk cat cut grep head mkdir mv rmdir tail tr wc; do
    ln -s "$(command -v "$bin")" "$TMP/compatible/$bin"
done
for bin in date id cmd pm am; do
    ln -s "$TMP/bin/$bin" "$TMP/compatible/$bin"
done
cat > "$TMP/bin/busybox-minimal" <<'MOCK'
#!/bin/sh
case "$1" in
    --list) printf '%s\n' awk mkdir timeout ;;
    timeout) shift; exec /usr/bin/timeout "$@" ;;
esac
MOCK
chmod +x "$TMP/bin/busybox-minimal"
export BERRY_KSU_BUSYBOX="$TMP/bin/busybox-minimal"
proc_connected
settime 35500
j="$(PATH="$TMP/compatible" /bin/sh "$MON" status)"
assert_json "$j" fcm_state=connected
test ! -e "$BERRY_FCM_DATA/mcs-monitor-$(cat "$TMP/bootid" | tr -cd 'A-Za-z0-9-').lockdir"
unset BERRY_KSU_BUSYBOX
echo 'PASS: monitor works without a BusyBox flock applet'

# A state path that is a directory must abort before publishing evidence or broadcasting.
export BERRY_FCM_DATA="$TMP/state-directory-data"
mkdir -p "$BERRY_FCM_DATA" "$BERRY_FCM_DATA/mcs-monitor.state"
echo 1 > "$BERRY_FCM_DATA/enabled"
: > "$MCS_AM_LOG"
proc_absent
settime 35000
if run monitor; then
    echo 'state directory unexpectedly allowed sample persistence' >&2
    exit 1
fi
requests 0
test ! -f "$BERRY_FCM_DATA/mcs-evidence"
echo 'PASS: invalid state target aborts sample and recovery transaction'

# A boot-id change must reset monotonic anchors without touching removed legacy paths.
export BERRY_FCM_DATA="$TMP/boot-transition-data"
mkdir -p "$BERRY_FCM_DATA"
echo 1 > "$BERRY_FCM_DATA/enabled"
proc_connected
settime 36001
j="$(run status)"
assert_json "$j" fcm_state=connected
echo next-boot > "$TMP/bootid"
j="$(run status)"
assert_json "$j" fcm_state=connected
echo 'PASS: boot transition reinitializes procfs monitoring safely'

# No dump or ss executable is supplied: this smoke test proves neither is needed.
if grep -Eqi 'dumpsys|ss -tnpe|BERRY_DUMPSYS_BIN|BERRY_SS_BIN' "$MON"; then
    echo 'legacy dumpsys/ss dependency remains' >&2
    exit 1
fi
echo 'PASS: KernelSU monitor has no dumpsys or ss dependency'
