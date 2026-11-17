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
need 'START_NOT_STICKY' "$src/AdbPairingService.java"
if grep -q 'START_REDELIVER_INTENT' "$src/AdbPairingService.java"; then
  echo 'Pairing code must never be redelivered on service restart' >&2
  exit 1
fi
if grep -q 'adbConnected' "$src/FcmMonitorService.java"; then
  echo 'Cached ADB transport would bypass mode changes' >&2
  exit 1
fi
need 'adb.autoConnectLocal(this)' "$src/FcmMonitorService.java"
need 'adb.connectStatic(prefs.host(), prefs.staticPort())' "$src/FcmMonitorService.java"
need 'shellInvalidate' "$src/AppPrefs.java"
need 'shellInvalidate' "$src/AdbPairingService.java"
need 'R.drawable.ic_bf_auth' "$src/AdbPairingService.java"
need 'R.drawable.ic_bf_fcm' "$src/FcmMonitorService.java"
need 'R.drawable.ic_bf_fcm' "$src/SettingsActivity.java"
need '@drawable/ic_bf_fcm' "$manifest"
need '37123:123456' "$src/AdbPairingService.java"
need '_adb-tls-pairing._tcp' "$src/AdbPairingService.java"
need 'NsdManager' "$src/AdbPairingService.java"
need 'localAddress' "$src/AdbPairingService.java"
need 'setOnApplyWindowInsetsListener' "$src/Ui.java"
need 'setTheme(R.style.AppThemeDark)' "$src/Ui.java"
need 'AppThemeDark' "$root/app/src/main/res/values/styles.xml"
need '@mipmap/ic_launcher' "$manifest"
need '@drawable/ic_berry_fcm' "$root/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
need '@color/berry_launcher_background' "$root/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
need '<monochrome' "$root/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
need 'BerryFCM"' "$manifest"
need 'SIGNATURE_PADDING_RSA_PSS' "$src/AdbConnectionManager.java"
need 'DIGEST_NONE' "$src/AdbConnectionManager.java"
need 'ENCRYPTION_PADDING_NONE' "$src/AdbConnectionManager.java"
need 'berry_fcm_adb_identity_v3' "$src/AdbConnectionManager.java"
need 'HorizontalScrollView' "$src/AuthActivity.java"
need 'shellRecentlyVerified' "$src/MonitorActivity.java"
need 'shellRecentlyVerified' "$src/GuardActivity.java"
need 'shellRecentlyVerified' "$src/BackgroundActivity.java"
need 'shellVerified' "$src/PrivilegeAccess.java"
need 'shell_verified_elapsed' "$src/AppPrefs.java"
need 'Math.abs(wallAge - elapsedAge)' "$src/AppPrefs.java"
need 'R.drawable.ic_bf_fcm' "$src/Ui.java"
need 'BerryFCM-v0.1.0-alpha' "$root/.github/workflows/build-apk.yml"
need 'BerryFCM-Guardian-v0.1.0-alpha' "$root/.github/workflows/build-apk.yml"
for asset in home fcm guard battery settings auth update diagnostics alert; do
  test -s "$root/app/src/main/res/drawable/ic_bf_$asset.xml" || {
    echo "Missing Material icon: $asset" >&2; exit 1;
  }
done
test -s "$root/app/src/main/res/drawable/ic_berry_fcm.xml"
test -s "$root/app/src/main/res/drawable/ic_berry_fcm_mono.xml"
need 'QUERY_ALL_PACKAGES' "$manifest"
need 'GuardOps.command' "$src/GuardActivity.java"
need 'GuardOps.command' "$src/FcmMonitorService.java"
need 'watchNinetySeconds' "$src/GuardActivity.java"
need 'heartbeatCommand' "$src/GuardOps.java"
need 'gmsBackgroundNetwork' "$src/BackgroundSettings.java"
need 'checkPolicies' "$src/DiagnosticsActivity.java"
need 'Socket socket = new Socket()' "$src/DiagnosticsActivity.java"
need 'socket.connect(new InetSocketAddress' "$src/DiagnosticsActivity.java"
need '无需 Root / ADB' "$src/DiagnosticsActivity.java"
need 'BackgroundSettings.optimize(this, shell)' "$src/BackgroundActivity.java"
need 'BackgroundSettings.restoreSelf(this, shell)' "$src/BackgroundActivity.java"
need 'self_op_previous_' "$src/BackgroundSettings.java"
need 'readAppOp' "$src/BackgroundSettings.java"
need '恢复未通过回读' "$src/BackgroundSettings.java"
need 'areNotificationsEnabled' "$src/BackgroundActivity.java"
need 'AppTask' "$src/RecentTasks.java"
need 'gh release create' "$root/.github/workflows/build-apk.yml"

for old in AccessibilityOps RestoreService RestoreTileService; do
  test ! -e "$src/$old.java" || { echo "Obsolete accessibility component: $old" >&2; exit 1; }
  if grep -q "$old" "$manifest"; then echo "Obsolete manifest component: $old" >&2; exit 1; fi
done
if grep -q 'WRITE_SECURE_SETTINGS\|BIND_ACCESSIBILITY_SERVICE' "$manifest"; then
  echo 'Unnecessary accessibility settings permission in manifest' >&2; exit 1
fi

# Exercise the actual plain-Java shell command builder with >10 selected apps.
classes="${RUNNER_TEMP:-/tmp}/berryfcm-guard-smoke"
mkdir -p "$classes"
javac -d "$classes" "$src/GuardOps.java" "$root/tests/GuardOpsSmoke.java"
java -cp "$classes" top.zesun.berryfcm.GuardOpsSmoke

echo 'PASS: APK navigation modules, notification ADB pairing, FCM guard, insets, tile, release workflow, and removed accessibility scope'
