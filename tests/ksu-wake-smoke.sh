#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/data" "$TMP/module/bin" "$TMP/bin"
export TEST_DIR="$TMP"
export PATH="$TMP/bin:$PATH"
sed 's|^DATA=/data/adb/berry_fcm_ksu$|DATA="$TEST_DIR/data"|' "$ROOT/module/service.sh" > "$TMP/module/service.sh"
cat > "$TMP/bin/id" <<'MOCK'
#!/bin/sh
echo 0
MOCK
cat > "$TMP/bin/getprop" <<'MOCK'
#!/bin/sh
echo 1
MOCK
cat > "$TMP/bin/sleep" <<'MOCK'
#!/bin/sh
echo "$1" >> "$TEST_DIR/sleeps"
rm -f "$TEST_DIR/module/module.prop"
MOCK
cat > "$TMP/module/bin/wake-timer-arm64" <<'MOCK'
#!/bin/sh
echo tick >> "$TEST_DIR/timer"
[ "$(wc -l < "$TEST_DIR/timer")" = 1 ] && exit 0
echo 'timerfd_create: fixture permission denied' >&2
exit 1
MOCK
cat > "$TMP/module/bin/fcm-monitor.sh" <<'MOCK'
echo "$1" >> "$TEST_DIR/samples"
MOCK
printf 'exit 0\n' > "$TMP/module/bin/ctl.sh"
chmod +x "$TMP/bin/"* "$TMP/module/bin/wake-timer-arm64"
touch "$TMP/module/module.prop"
echo 1 > "$TMP/data/enabled"
echo timed > "$TMP/data/wake-mode"
sh "$TMP/module/service.sh"
test "$(cat "$TMP/data/wake-status")" = fallback
test "$(wc -l < "$TMP/samples")" -eq 2
test "$(wc -l < "$TMP/timer")" -eq 2
test "$(cat "$TMP/sleeps")" = 30
grep -q '3 分钟定时器触发' "$TMP/data/guard.log"
grep -q 'fixture permission denied' "$TMP/data/guard.log"
test ! -d "$TMP/data/service.lock"
echo 'PASS: timed wake samples GMS, failure falls back to passive polling'
