#!/system/bin/sh
DIR="${0%/*}"
echo '=== FCM 检测 ==='
sh "$DIR/bin/ctl.sh" check || exit 1
echo '=== 一键修复 ==='
sh "$DIR/bin/ctl.sh" fix
