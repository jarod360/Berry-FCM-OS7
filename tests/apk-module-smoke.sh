#!/bin/sh
set -eu
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
src="$root/app/src/main/java/top/zesun/berryfcm"
manifest="$root/app/src/main/AndroidManifest.xml"
need() {
  grep -q "$1" "$2" || { echo "MISSING: $1 in $2" >&2; exit 1; }
}
for class in MainActivity AuthActivity MonitorActivity GuardActivity BackgroundActivity DiagnosticsActivity SettingsActivity UpdateActivity AdbPairingService FcmTileService FcmMonitorService; do
  test -s "$src/$class.java" || { echo "MISSING: $class" >&2; exit 1; }
  need "$class" "$manifest"
done
need 'RemoteInput' "$src/AdbPairingService.java"
need '_adb-tls-pairing._tcp' "$src/AdbPairingService.java"
need 'NsdManager' "$src/AdbPairingService.java"
need 'localAddress' "$src/AdbPairingService.java"
need 'setOnApplyWindowInsetsListener' "$src/Ui.java"
need 'QUERY_ALL_PACKAGES' "$manifest"
need 'GuardOps.command' "$src/GuardActivity.java"
need 'GuardOps.command' "$src/FcmMonitorService.java"
need 'AppTask' "$src/RecentTasks.java"
need 'gh release create' "$root/.github/workflows/build-apk.yml"

for old in AccessibilityOps RestoreService RestoreTileService; do
  test ! -e "$src/$old.java" || { echo "Obsolete accessibility component: $old" >&2; exit 1; }
  if grep -q "$old" "$manifest"; then echo "Obsolete manifest component: $old" >&2; exit 1; fi
done
if grep -q 'WRITE_SECURE_SETTINGS\|BIND_ACCESSIBILITY_SERVICE' "$manifest"; then
  echo 'Unnecessary accessibility settings permission in manifest' >&2; exit 1
fi

echo 'PASS: APK navigation modules, notification ADB pairing, FCM guard, insets, tile, release workflow, and removed accessibility scope'
