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
# In wireless mode ADB launches Priv Kit; all ordinary commands use its Binder.
need 'adb.executeOnce(this, mode, prefs.host(), prefs.staticPort(), command)' "$src/FcmMonitorService.java"
need 'PrivKitShell.execute(this, command)' "$src/FcmMonitorService.java"
need 'PrivKitShell.execute(context, command)' "$src/AdbShell.java"
need 'PrivKitShell.start(context)' "$src/PrivilegeAccess.java"
need 'KIT.startCommand(request, 45000L, continuation)' "$src/PrivKitShell.java"
need 'io.github.priv-kit:priv-core:0.20.0' "$root/app/build.gradle"
need 'autoAttempted' "$src/PrivKitShell.java"
need 'RECOVERY_COOLDOWN_MS' "$src/PrivKitShell.java"
need 'getLifecycleBinder().isBinderAlive()' "$src/PrivKitShell.java"
need 'synchronized String executeOnce' "$src/AdbConnectionManager.java"
# Check the command transaction itself, not only the pairing cleanup.
transaction="$(sed -n '/synchronized String executeOnce(/,/synchronized String shell(/p' "$src/AdbConnectionManager.java")"
printf '%s\n' "$transaction" | grep -q 'finally {'
printf '%s\n' "$transaction" | grep -q 'disconnectSession();'
need 'return adb.executeOnce' "$src/AdbShell.java"
need 'return adb.executeOnce' "$src/PrivilegeAccess.java"
need 'Shizuku.bindUserService' "$src/ShizukuShell.java"
need 'Binder.getCallingUid() != ownerUid' "$src/BerryShellUserService.java"
need 'Shizuku.requestPermission' "$src/AuthActivity.java"
need 'android.permission.INTERACT_ACROSS_USERS_FULL' "$manifest"
need 'dev.rikka.shizuku:api:13.1.5' "$root/app/build.gradle"
test -s "$root/app/src/main/aidl/top/zesun/berryfcm/IBerryShell.aidl"
need 'shellInvalidate' "$src/AppPrefs.java"
need 'R.drawable.ic_bf_auth' "$src/AdbPairingService.java"
need 'R.drawable.ic_bf_fcm' "$src/FcmMonitorService.java"
need 'R.drawable.ic_bf_fcm' "$src/SettingsActivity.java"
need '@drawable/ic_bf_fcm' "$manifest"
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
# Single-tap pairing starts the notification, then opens wireless settings.
need '开始配对并打开无线调试' "$src/AuthActivity.java"
need 'openWirelessDebugging()' "$src/AuthActivity.java"
need 'Settings\$WirelessDebuggingActivity' "$src/AuthActivity.java"
need 'onRequestPermissionsResult' "$src/AuthActivity.java"
need 'prefs.monitorEnabled(true)' "$src/AdbPairingService.java"
need 'FcmMonitorService.start(this)' "$src/AdbPairingService.java"
need 'lastAttemptAt' "$src/FcmMonitorService.java"
need 'authorizationReady()' "$src/MainActivity.java"
need 'monitorButton.setText("去授权")' "$src/MainActivity.java"
need 'disclosure' "$src/Ui.java"
need 'View.generateViewId()' "$src/SettingsActivity.java"
# Pair once, do not gate monitoring on a two-minute manual Shell probe.
need 'boolean adbPaired()' "$src/AppPrefs.java"
need 'void markAdbPaired()' "$src/AppPrefs.java"
need 'prefs.monitorEnabled(true)' "$src/AdbPairingService.java"
need 'FcmMonitorService.start(this)' "$src/AdbPairingService.java"
need 'lastStartAt' "$src/FcmMonitorService.java"
need 'lastAttemptAt' "$src/MonitorActivity.java"
need 'monitorButton.setText("去授权")' "$src/MainActivity.java"
need 'View.GONE' "$src/Ui.java"
# Selected-first app guardian and conservative system-app eligibility.
need 'private void renderSelected()' "$src/GuardActivity.java"
need 'private void togglePicker()' "$src/GuardActivity.java"
need 'picker.setVisibility(View.GONE)' "$src/GuardActivity.java"
need 'private void loadCandidates()' "$src/GuardActivity.java"
need 'if (open && !scanned && !loading) loadCandidates()' "$src/GuardActivity.java"
need 'getLaunchIntentForPackage' "$src/GuardActivity.java"
need 'FLAG_INSTALLED' "$src/GuardActivity.java"
need 'FLAG_PERSISTENT' "$src/GuardActivity.java"
need 'showSystem.setChecked(false)' "$src/GuardActivity.java"
need 'getApplicationIcon(app.pkg)' "$src/GuardActivity.java"
# Original FCM icon uses shield and pulse rather than the old SMS glyph.
need 'strokeColor' "$root/app/src/main/res/drawable/ic_bf_fcm.xml"
need 'strokeColor' "$root/app/src/main/res/drawable/ic_berry_fcm.xml"

need 'View.GONE' "$src/UpdateActivity.java"
need 'Drawable icon = icons.get' "$src/GuardActivity.java"
need 'ic_berry_fcm_mono' "$root/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
if grep -q 'if (!prefs.shellRecentlyVerified()) state =' "$src/MonitorActivity.java"; then
  echo 'Sampling UI must not treat verification TTL as loss of ADB pairing' >&2
  exit 1
fi

need 'authorizationReady()' "$src/MonitorActivity.java"
need 'PrivilegeAccess.readyForUserAction(this)' "$src/GuardActivity.java"
need 'PrivilegeAccess.readyForUserAction(this)' "$src/BackgroundActivity.java"
need 'PrivilegeAccess.readyForUserAction(this)' "$src/FcmTileService.java"
need 'checkedRead' "$src/BackgroundSettings.java"
need 'BERRY_POLICY_READ_OK' "$src/BackgroundSettings.java"
need 'dozePresent' "$src/BackgroundSettings.java"
need 'putLong("last_run", System.currentTimeMillis())' "$src/GuardActivity.java"
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
if grep -q 'BIND_ACCESSIBILITY_SERVICE' "$manifest" || grep 'WRITE_SECURE_SETTINGS' "$manifest" | grep -vq 'tools:node="remove"'; then
  echo 'Unnecessary accessibility settings permission in manifest' >&2; exit 1
fi

# Service lifecycle and transport consistency: a preference is not proof that
# the monitor is alive; a previous session is not live connection evidence.
need 'sampleAgeSeconds' "$src/FcmMonitorService.java"
need 'sample_session' "$src/FcmMonitorService.java"
need 'sample_elapsed' "$src/FcmMonitorService.java"
need 'sample_mode' "$src/FcmMonitorService.java"
need 'registerDefaultNetworkCallback' "$src/FcmMonitorService.java"
need 'unregisterNetworkCallback' "$src/FcmMonitorService.java"
need 'ACTION_DEVICE_IDLE_MODE_CHANGED' "$src/FcmMonitorService.java"
need 'lock.acquire(90000L)' "$src/FcmMonitorService.java"
need 'lock.release()' "$src/FcmMonitorService.java"
need 'resumeIfNeeded' "$src/MainActivity.java"
need 'sampleAgeSeconds' "$src/MonitorActivity.java"
need 'android.permission.WAKE_LOCK' "$manifest"
need 'job.get(45, TimeUnit.SECONDS)' "$src/AdbConnectionManager.java"
need 'process.waitFor(45, TimeUnit.SECONDS)' "$src/RootShell.java"
need 'PrivilegeAccess.probe' "$src/AuthActivity.java"
need 'prefs.activateMode' "$src/AuthActivity.java"
need 'connectionKey' "$src/AppPrefs.java"
if grep -q 'prefs.mode(mode);' "$src/AuthActivity.java"; then
  echo 'Browsing authorization tabs must not switch the active transport' >&2; exit 1
fi

# Exercise the actual plain-Java shell command builder with >10 selected apps.
classes="${RUNNER_TEMP:-/tmp}/berryfcm-guard-smoke"
mkdir -p "$classes"
javac -d "$classes" "$src/GuardOps.java" "$root/tests/GuardOpsSmoke.java"
java -cp "$classes" top.zesun.berryfcm.GuardOpsSmoke
javac -d "$classes" "$src/LaunchCooldown.java" "$root/tests/LaunchCooldownSmoke.java"
java -cp "$classes" top.zesun.berryfcm.LaunchCooldownSmoke
need 'PrivilegeConfig.INSTANCE.configure(3600000L, false)' "$src/PrivKitShell.java"
need 'PrivKitShell.initialize()' "$src/BerryFcmApp.java"
need 'rememberLaunch(context)' "$src/PrivKitShell.java"

echo 'PASS: APK navigation modules, notification ADB pairing, FCM guard, insets, tile, release workflow, and removed accessibility scope'

# Optional Shizuku service uses public Android SDK exceptions.
if grep -q "android.os.ServiceSpecificException" "$src/BerryShellUserService.java"; then
  echo "Hidden Android exception import in Shizuku service"; exit 1
fi

# One identity and the official upstream discovery/authorization/start path.
need 'adb.discoverPairingPort(6000L, continuation)' "$src/PrivKitShell.java"
need 'adb.pair(code, port, 15000L, continuation)' "$src/PrivKitShell.java"
need 'adb.checkPairing(port, 15000L, continuation)' "$src/PrivKitShell.java"
need 'KIT.startAdb(options, 20000L, ADB_NAME' "$src/PrivKitShell.java"
need 'PrivKitShell.discoverPairingPort()' "$src/AdbPairingService.java"
need 'gate.submit(generation, port)' "$src/AdbPairingService.java"
need 'gate.submitManual(generation, entered)' "$src/AdbPairingService.java"
need 'MANUAL_FALLBACK_DELAY_MS' "$src/AdbPairingService.java"
need 'last_startup_trace' "$src/PrivKitShell.java"
need 'getNotificationChannel' "$src/AuthActivity.java"
need 'gate.ready(generation, found)' "$src/AdbPairingService.java"
need 'PrivKitShell.pair(this, manual ? selectedPort : null, code)' "$src/AdbPairingService.java"
need 'if (!authorizationReady())' "$src/MainActivity.java"
need 'monitorButton.setText("立即检查")' "$src/MainActivity.java"
need 'if (!authorizationReady()) { go(AuthActivity.class); return; }' "$src/MonitorActivity.java"
need 'if (authorizationReady()) FcmMonitorService.resumeIfNeeded(this)' "$src/MainActivity.java"
if grep -Eq '立即采样|等待独立服务|localPortListening' "$src/MainActivity.java" "$src/AdbPairingService.java"; then
  echo 'Home must gate monitoring by authorization; pairing must not recheck loopback'; exit 1
fi
need 'PrivKitShell.start(this)' "$src/AdbPairingService.java"
need 'PrivKitShell.execute(this, "id")' "$src/AdbPairingService.java"
need 'prefs.rejectAdbIdentity()' "$src/PrivKitShell.java"
need 'privkit_pairing_history' "$src/AppPrefs.java"
need 'startAfterSettings && leftForSettings' "$src/AuthActivity.java"
if grep -Eq 'AdbConnectionManager|executeOnce\(' "$src/PrivKitShell.java" "$src/AdbPairingService.java"; then
  echo 'Wireless pairing and bootstrap must not call legacy libadb'; exit 1
fi
if grep -Eq 'autoConnectLocal|pairLocal|connectTls' "$src/AdbConnectionManager.java"; then
  echo 'Legacy manager must be static TCP only'; exit 1
fi
javac -d "$classes" "$src/PairingGate.java" "$root/tests/PairingGateSmoke.java"
java -cp "$classes" top.zesun.berryfcm.PairingGateSmoke
