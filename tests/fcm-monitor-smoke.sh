#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
MON="$ROOT/module/bin/fcm-monitor.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/bin" "$TMP/data"
cat > "$TMP/bin/cmd" <<'MOCK'
#!/bin/sh
[ "$1 $2 $3 $4" = 'package list packages -U' ] && echo 'package:com.google.android.gms uid:10200,99910200'
MOCK
cat > "$TMP/bin/pm" <<'MOCK'
#!/bin/sh
exit 0
MOCK
cat > "$TMP/bin/dumpsys" <<'MOCK'
#!/bin/sh
echo '    User 0: ceDataInode=1 installed=true stopped=false suspended=false hidden=false'
MOCK
cat > "$TMP/bin/ss" <<'MOCK'
#!/bin/sh
cat "$BERRY_SS_FILE"
MOCK
cat > "$TMP/bin/nslookup" <<'MOCK'
#!/bin/sh
[ "${BERRY_TEST_DNS:-on}" = on ] || exit 1
printf 'Server: 192.168.1.1\nAddress 1: 192.168.1.1\nName: %s\nAddress 1: 142.250.107.188\n' "$1"
MOCK
cat > "$TMP/bin/am" <<'MOCK'
#!/bin/sh
echo "$*" >> "$BERRY_AM_LOG"
echo 'Broadcast completed: result=0'
MOCK
chmod +x "$TMP/bin/"*
echo 1000.00 > "$TMP/uptime"
echo test-boot > "$TMP/bootid"
echo 1 > "$TMP/data/enabled"
: > "$TMP/ss"
: > "$TMP/am.log"
export PATH="$TMP/bin:/usr/bin:/bin"
export BERRY_FCM_TEST_MODE=1 BERRY_FCM_DATA="$TMP/data"
export BERRY_UPTIME_FILE="$TMP/uptime" BERRY_BOOT_ID_FILE="$TMP/bootid"
export BERRY_SS_FILE="$TMP/ss" BERRY_AM_LOG="$TMP/am.log"
run() { /bin/sh "$MON" "$1"; }
json() { run status | tail -n 1; }
assert_json() { python3 - "$1" "$2" <<'PY'
import json,sys
j=json.loads(sys.argv[1]); k,v=sys.argv[2].split('=',1)
assert str(j[k])==v,(k,j[k],v,j)
PY
}

# Dedicated MCS port is accepted for the GMS UID.
cat > "$TMP/ss" <<'SOCK'
ESTAB 0 0 192.168.1.8:45000 142.250.107.188:5228 users:("gms",pid=10,fd=1) uid:10200 ino:101
SOCK
j="$(json)"; assert_json "$j" 'fcm_state=connected'; assert_json "$j" 'fcm_transport=tcp5228'

# 443 is accepted only when its peer matches a resolved mtalk address.
cat > "$TMP/ss" <<'SOCK'
ESTAB 0 0 192.168.1.8:45001 142.250.107.188:443 users:("gms",pid=10,fd=2) uid:10200 ino:102
SOCK
printf '1030.00 0.00\n' > "$TMP/uptime"
j="$(json)"; assert_json "$j" 'fcm_state=connected'; assert_json "$j" 'fcm_transport=tcp443'

# An unrelated GMS HTTPS socket is not FCM evidence.
cat > "$TMP/ss" <<'SOCK'
ESTAB 0 0 192.168.1.8:45002 2a04:4e42:68::347:443 users:("gms",pid=10,fd=3) uid:10200 ino:103
SOCK
printf '1060.00 0.00\n' > "$TMP/uptime"
j="$(json)"; assert_json "$j" 'fcm_state=disconnected'; assert_json "$j" 'fcm_missing_seconds=0'

# At 120 seconds the monitor sends one targeted reconnect; it does not claim success.
printf '1180.00 0.00\n' > "$TMP/uptime"
run monitor
[ "$(wc -l < "$TMP/am.log" | tr -d ' ')" = 1 ]
j="$(json)"; assert_json "$j" 'fcm_last_result=requested'; assert_json "$j" 'fcm_attempts=1'

# Observing the trusted socket again records a real restoration.
cat > "$TMP/ss" <<'SOCK'
ESTAB 0 0 192.168.1.8:45003 142.250.107.188:443 users:("gms",pid=10,fd=4) uid:10200 ino:104
SOCK
printf '1210.00 0.00\n' > "$TMP/uptime"
j="$(json)"; assert_json "$j" 'fcm_state=connected'; assert_json "$j" 'fcm_last_result=restored'; assert_json "$j" 'fcm_outages=1'

# If mtalk DNS cannot be resolved and only an arbitrary GMS:443 exists, state is unknown,
# not disconnected; it must not accumulate outage time or issue another reconnect.
: > "$TMP/data/mtalk-dns-ips.txt"; : > "$TMP/data/mtalk-learned-ips.txt"; rm -f "$TMP/data/mtalk-dns-time"
export BERRY_TEST_DNS=off
printf '1240.00 0.00\n' > "$TMP/uptime"
j="$(json)"; assert_json "$j" 'fcm_state=unknown'; assert_json "$j" 'fcm_missing_seconds=0'
[ "$(wc -l < "$TMP/am.log" | tr -d ' ')" = 1 ]

echo 'PASS: 5228, mtalk-verified 443, unrelated 443 rejection, 120s reconnect, restoration, unknown-safe behavior'
