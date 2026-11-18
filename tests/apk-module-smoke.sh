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
need 'requireShellIdentity(adb.executeOnce' "$src/PrivilegeAccess.java"
need 'Shizuku.bindUserService' "$src/ShizukuShell.java"
need 'Binder.getCallingUid() != ownerUid' "$src/BerryShellUserService.java"
need 'Shizuku.requestPermission' "$src/AuthActivity.java"
need 'android.permission.INTERACT_ACROSS_USERS_FULL' "$manifest"
need 'dev.rikka.shizuku:api:13.1.5' "$root/app/build.gradle"
test -s "$root/app/src/main/aidl/top/zesun/berryfcm/IBerryShell.aidl"
need 'shellInvalidate' "$src/AppPrefs.java"
need 'R.drawable.ic_bf_auth' "$src/AdbPairingService.java"
need 'R.drawable.ic_bf_fcm' "$src/FcmMonitorService.java"
need 'R.drawable.ic_bf_fcm' "$src/AuthActivity.java"
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
need 'group.addView(tab, params)' "$src/AuthActivity.java"
# Single-tap pairing starts the notification, then opens wireless settings.
need 'button(parent, "开始配对"' "$src/AuthActivity.java"
need 'openWirelessDebugging()' "$src/AuthActivity.java"
need 'Settings\$WirelessDebuggingActivity' "$src/AuthActivity.java"
need 'onRequestPermissionsResult' "$src/AuthActivity.java"
need 'prefs.enableMonitorOnFirstAuthorization()' "$src/AdbPairingService.java"
need 'FcmMonitorService.start(this)' "$src/AdbPairingService.java"
need 'lastAttemptAt' "$src/FcmMonitorService.java"
need 'authorizationReady()' "$src/MainActivity.java"
need 'monitorButton.setText("去授权")' "$src/MainActivity.java"
need 'disclosure' "$src/Ui.java"
need 'View.generateViewId()' "$src/SettingsActivity.java"
# Pair once, do not gate monitoring on a two-minute manual Shell probe.
need 'boolean adbPaired()' "$src/AppPrefs.java"
need 'void markAdbPaired()' "$src/AppPrefs.java"
need 'prefs.enableMonitorOnFirstAuthorization()' "$src/AdbPairingService.java"
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
need 'GuardOps.commandBatches' "$src/GuardActivity.java"
need 'GuardOps.commandBatches' "$src/FcmMonitorService.java"
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
need 'areNotificationsEnabled' "$src/AuthActivity.java"
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
need 'PrivKitShell.pair(this, selectedPort, code)' "$src/AdbPairingService.java"
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

# No unsolicited tile or persistent first-run unauthorized monitoring.
# Check the specific tile component, not an unrelated disabled component.
awk '/android:name="\.FcmTileService"/ {tile=1; next} tile && /android:enabled="false"/ {ok=1; exit} tile && /<service|<intent-filter/ {exit} END {exit !ok}' "$manifest"
# Neither authorization success nor service startup may opt in to the tile.
if grep -n 'FcmTileService.setOffered' "$src/AdbPairingService.java" "$src/FcmMonitorService.java" "$src/PrivKitShell.java" "$src/BerryFcmApp.java" "$src/BootReceiver.java"; then
  echo 'Only an explicit user action may expose the Quick Settings tile' >&2; exit 1
fi
need 'FcmTileService.setOffered(this, true)' "$src/AuthActivity.java"
need 'Tile.STATE_UNAVAILABLE' "$src/FcmTileService.java"
need 'canRunInBackground(context)' "$src/FcmMonitorService.java"
need 'canRunInBackground(this)' "$src/FcmMonitorService.java"
need 'STOP_FOREGROUND_REMOVE' "$src/FcmMonitorService.java"

need 'private void addTile()' "$src/AuthActivity.java"
need 'BackgroundSettings.backgroundRestriction(this)' "$src/AuthActivity.java"
# Old private/OEM or duplicate settings assertion removed.
need 'onTileAdded()' "$src/FcmTileService.java"
need 'onTileRemoved()' "$src/FcmTileService.java"

# Auxiliary permissions are advisory; core monitor and guard cannot depend on them.
need 'createAuxiliaryPermissions()' "$src/AuthActivity.java"
need 'refreshAuxiliaryPermissions()' "$src/AuthActivity.java"
need 'BackgroundSettings.associatedStart(this)' "$src/AuthActivity.java"
need 'BackgroundSettings.powerSettings(this)' "$src/AuthActivity.java"
need 'BackgroundSettings.monitorNotifications(this)' "$src/AuthActivity.java"
if grep -nE 'BackgroundSettings\.(batteryExempt|originAutoStartStatus|backgroundRestriction|monitorNotificationStatus)|FcmTileService\.(offered|displayStatus)|areNotificationsEnabled' "$src/PrivilegeAccess.java" "$src/FcmMonitorService.java" "$src/BootReceiver.java"; then
  echo 'Optional system permissions must not block background task execution' >&2; exit 1
fi

need '立即更新' "$src/UpdateActivity.java"
need 'pollDownload()' "$src/UpdateActivity.java"
need 'waiting_install_permission' "$src/UpdateActivity.java"
need 'if (!resumed) return' "$src/UpdateActivity.java"
need 'code != expectedVersion' "$src/UpdateActivity.java"

# Concentrated regression: identity, optional vendor permission evidence and release promotion.
need 'requireShellIdentity(PrivKitShell.execute(context, "id"), false)' "$src/PrivilegeAccess.java"
# Old private/OEM or duplicate settings assertion removed.
# Old private/OEM or duplicate settings assertion removed.
# Old private/OEM or duplicate settings assertion removed.
# Removed bulk AppOps diagnostic; negative guard at end of file.
need 'accepted_run_id' "$root/.github/workflows/build-apk.yml"
need 'gh run download "$RUN_ID"' "$root/.github/workflows/build-apk.yml"
need 'Promote existing signed APK' "$root/.github/workflows/build-apk.yml"

need 'PendingIntent.getService(this, 10, reply' "$src/AdbPairingService.java"
need 'setTimeoutAfter(15000L)' "$src/AdbPairingService.java"
need 'enableMonitorOnFirstAuthorization()' "$src/AppPrefs.java"
if grep -q 'Manifest.permission.NEARBY_WIFI_DEVICES' "$src/AuthActivity.java"; then
  echo 'Do not require nearby WiFi permission for Priv Kit NSD' >&2; exit 1
fi

# Shared state and UI lifecycle regression.
need 'MonitorStatus.resolve' "$src/MainActivity.java"
need 'MonitorStatus.resolve' "$src/MonitorActivity.java"
need '后台监控运行中（连接详情请在应用内查看）' "$src/FcmMonitorService.java"
need 'syncChecked(enabled, prefs.monitorEnabled())' "$src/MonitorActivity.java"
need 'syncChecked(running, prefs.guardEnabled())' "$src/GuardActivity.java"
need 'syncChecked(monitorSwitch, prefs.monitorEnabled())' "$src/BackgroundActivity.java"
need 'finally { syncingSwitches = previous; }' "$src/Ui.java"
need 'watchFuture.cancel(true)' "$src/GuardActivity.java"
need 'runSafe(manual)' "$src/FcmMonitorService.java"
need 'guardKey.equals(p.connectionKey())' "$src/FcmMonitorService.java"
need 'tileRequestPending' "$src/AuthActivity.java"
# Old private/OEM or duplicate settings assertion removed.
status_classes="$(mktemp -d)"
javac -d "$status_classes" "$src/MonitorStatus.java" "$root/tests/MonitorStatusSmoke.java"
java -cp "$status_classes" top.zesun.berryfcm.MonitorStatusSmoke
rm -rf "$status_classes"

need 'lastAttemptKey = attemptKey' "$src/FcmMonitorService.java"
need 'stopSelfResult(startId)' "$src/FcmMonitorService.java"
need 'error_session' "$src/FcmMonitorService.java"
need 'registerOnSharedPreferenceChangeListener(reportListener)' "$src/GuardActivity.java"
need 'unregisterOnSharedPreferenceChangeListener(reportListener)' "$src/GuardActivity.java"

# Priv Kit requires host API compatibility before initialization and platform TLS.
need 'hiddenapibypass:6.2' "$root/app/build.gradle"
need 'attachBaseContext(Context base)' "$src/BerryFcmApp.java"
need 'HiddenApiBypass.addHiddenApiExemptions("L")' "$src/BerryFcmApp.java"
if grep -q 'Security.insertProviderAt' "$src/BerryFcmApp.java"; then
  echo 'Do not replace platform TLS provider used by Priv Kit' >&2; exit 1
fi
need 'PrivKitShell.recoverOnForeground(this)' "$src/MainActivity.java"
need 'PrivKitShell.recoverOnForeground(this)' "$src/AuthActivity.java"
need 'hasStoredIdentity(context)' "$src/PrivKitShell.java"
need 'foregroundRecovery.compareAndSet(false, true)' "$src/PrivKitShell.java"
echo 'PASS: Priv Kit host integration and persisted-identity recovery wiring'

# Android 17 permission, TLS port and explicit service lifecycle.
need 'targetSdk 37' "$root/app/build.gradle"
need 'android.permission.ACCESS_LOCAL_NETWORK' "$root/app/src/main/AndroidManifest.xml"
need 'requestLocalNetworkIfNeeded' "$src/AuthActivity.java"
need 'systemConnectPort()' "$src/PrivKitShell.java"
need 'service.adb.tls.port' "$src/PrivKitShell.java"
need 'stopManually(this)' "$src/AuthActivity.java"
need 'ShizukuShell.stopBinding()' "$src/AuthActivity.java"
need 'prefs.authTab(mode)' "$src/AuthActivity.java"

need 'PrivKitShell.pair(this, selectedPort, code)' "$src/AdbPairingService.java"
need 'return;' "$src/AdbPairingService.java"

# ADB startup crash regression: 0/0 => nullable port for NSD (no Integer.intValue NPE)
need 'AdbConnectPort.select(manual, tls)' "$src/PrivKitShell.java"
need 'ShizukuShell.isRunning()' "$src/PrivilegeAccess.java"
tmp_adb_port="$(mktemp -d)"
javac -d "$tmp_adb_port" "$src/AdbConnectPort.java" "$root/tests/AdbConnectPortSmoke.java"
java -cp "$tmp_adb_port" top.zesun.berryfcm.AdbConnectPortSmoke
rm -rf "$tmp_adb_port"

# Compact authorization controls and selected method state.
need 'detail.setText("当前来源：" + methodName(currentMode)' "$src/AuthActivity.java"
need 'pendingAction = "正在验证 "' "$src/AuthActivity.java"
need 'confirmSwitch(this::startCandidateService)' "$src/AuthActivity.java"
need 'AdbPairingService.isAwaitingInput()' "$src/AuthActivity.java"
need 'openedPairSettings && leftForPairSettings' "$src/AuthActivity.java"
need 'PAIR_INPUT_TIMEOUT_MS' "$src/AdbPairingService.java"
need 'isCodeSubmitted()' "$src/AdbPairingService.java"
if grep -q '当前授权正在进行，请先完成或取消' "$src/AuthActivity.java"; then
  echo 'Browsing authorization methods must remain available' >&2; exit 1
fi
need 'pendingAction = "正在停止 "' "$src/AuthActivity.java"
need 'pendingAction = "正在重启 "' "$src/AuthActivity.java"
if grep -q 'busy ? "处理中"' "$src/AuthActivity.java"; then
  echo 'Generic authorization processing status is prohibited' >&2; exit 1
fi
need 'if (isSelectedRunning()) stopSelectedService()' "$src/AuthActivity.java"
need 'stateToggle.setContentDescription((running ? "停止" : "启动")' "$src/AuthActivity.java"
need 'errorSummary.setText(displayed.replace' "$src/AuthActivity.java"
need 'errorLog.setText(displayed +' "$src/AuthActivity.java"
need 'ShizukuShell.isRunning()' "$src/AuthActivity.java"

# Do not expose irrelevant full AppOps dump as OEM permission diagnosis.
if grep -q 'cmd appops get top.zesun.berryfcm 2>&1' "$src/AuthActivity.java"; then
  echo 'Full Android AppOps is not Vivo permission status' >&2; exit 1
fi
need 'permissionText(associatedRow, "关联启动", "需在系统确认")' "$src/AuthActivity.java"
need 'permissionText(highPowerRow, "允许后台高耗电", "需在系统确认")' "$src/AuthActivity.java"

# Keep optional permissions out of the main authorization view.
need 'disclosure(permissionCard, "自启动、电池和后台权限", false)' "$src/AuthActivity.java"
# Old private/OEM or duplicate settings assertion removed.

# No automatic ADB recovery when Android 17 local network authorization is absent.
need 'PrivKitShell.localNetworkAllowed(context)' "$src/PrivilegeAccess.java"

# Theme-aware toggle must use accessible Ui palette, not private Ui.dark.
need 'toggleSurface.setColor(surface)' "$src/AuthActivity.java"

# Actionable authorization UI and truthful OriginOS permission state.
need 'disclosure(chooser, "选择与管理授权方式", true)' "$src/AuthActivity.java"
need 'permissionText(autoStartRow, "自启动", "需在系统确认")' "$src/AuthActivity.java"
need 'step("Root 授权"' "$src/AuthActivity.java"
need 'step("Shizuku "' "$src/AuthActivity.java"
need 'text(caption, 16, true, ink)' "$src/Ui.java"
need '后台监控运行通知' "$src/FcmMonitorService.java"
need 'new Notification.Builder(this, CHANNEL)' "$src/FcmMonitorService.java"
need 'setColor(android.graphics.Color.WHITE)' "$src/AdbPairingService.java"
if grep -q 'readAutoStartPermission\|厂商权限检测诊断\|监控通知低干扰模式' "$src/AuthActivity.java"; then
  echo 'Misleading OEM status or excessive notification UI remains' >&2; exit 1
fi
