package top.zesun.berryfcm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Foreground monitoring runs independently of activities; all shell writes are serialized. */
public final class FcmMonitorService extends Service {
    private static final String SNAPSHOT = "berry_fcm_monitor_snapshot";
    private static final String CHANNEL = "berry_fcm_monitor_active";
    private static final String QUIET_CHANNEL = "berry_fcm_monitor_quiet";
    private static final int NOTIFICATION_ID = 7212;
    private static final String CHECK = "berryfcm.monitor.check";
    private static final String RECONNECT = "berryfcm.monitor.reconnect";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean scheduled;
    private boolean installed;
    private static volatile String activeSession = "";
    private final String session = UUID.randomUUID().toString();
    private static volatile long lastResumeRequest;
    private ConnectivityManager networkManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver resumeReceiver;
    private volatile long networkSignal;
    private long consumedNetworkSignal;
    private long lastCheckElapsed;
    private long nextAttemptElapsed;
    private int failureStreak;
    private final AtomicBoolean checkQueued = new AtomicBoolean();
    private final AtomicBoolean reconnectQueued = new AtomicBoolean();
    private String installedMode;
    private String lastAttemptKey = "";
    private java.util.concurrent.ScheduledFuture<?> periodic;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private int newestStartId;
    private AdbConnectionManager adb;
    private long lastGuardAt;
    private String lastGuardKey = "";
    private java.util.Set<String> lastGuardTargets = java.util.Collections.emptySet();
    private Network observedNetwork;
    private String lastNotificationText = "";
    private long networkChangedAt;

    static boolean start(Context context) { return start(context, null); }
    static boolean isRunning() { return !activeSession.isEmpty(); }

    // Called only from a visible activity. Restore existing enabled tasks,
    // without changing the user's settings or bypassing a force-stop.
    static void resumeIfNeeded(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) return;
        if (!PrivilegeAccess.canRunInBackground(context)) return;
        long now = SystemClock.elapsedRealtime();
        long age = sampleAgeSeconds(context);
        if (isRunning() && (!prefs.monitorEnabled() || (age >= 0 && age <= 90)
                || nextAttemptIn(context) > 0)) return;
        if (lastResumeRequest > 0 && now - lastResumeRequest < 15000) return;
        lastResumeRequest = now;
        checkNow(context);
    }

    static long sampleAgeSeconds(Context context) {
        SharedPreferences stored = context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE);
        if (!isRunning() || !activeSession.equals(stored.getString("sample_session", ""))) return -1;
        if (!new AppPrefs(context).connectionKey().equals(stored.getString("sample_mode", ""))) return -1;
        long at = stored.getLong("sample_elapsed", -1);
        long age = SystemClock.elapsedRealtime() - at;
        return at >= 0 && age >= 0 ? age / 1000 : -1;
    }

    static String serviceStatus(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) return "未启用";
        if (!isRunning()) return "后台服务未运行";
        if (!prefs.monitorEnabled()) return "应用守护运行中";
        long age = sampleAgeSeconds(context);
        if (!currentError(context).isEmpty()) return "采样失败，后台重试中";
        if (age < 0 || age > 90) return "等待本次有效采样";
        return "后台服务运行中";
    }
    static void checkNow(Context context) { start(context, CHECK); }
    // Preserve the previously selected notification channel across upgrades.
    // Never switch to a different channel to evade a user's notification setting.
    static String monitorChannelId(Context context) {
        return new AppPrefs(context).quietMonitor() ? QUIET_CHANNEL : CHANNEL;
    }

    static void ensureMonitorChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        String id = monitorChannelId(context);
        if (manager.getNotificationChannel(id) == null) {
            NotificationChannel channel = new NotificationChannel(id,
                    QUIET_CHANNEL.equals(id) ? "FCM 静音监测" : "FCM 后台监测",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }
    static String manualReconnect(Context context) {
        return start(context, RECONNECT)
                ? "正在安排重连，请查看本次手动重连结果"
                : "未安排重连，请检查监控开关和核心授权";
    }
    static String manualReconnectResult(Context context) {
        SharedPreferences stored = context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE);
        return !activeSession.isEmpty()
                && activeSession.equals(stored.getString("manual_session", ""))
                && new AppPrefs(context).connectionKey().equals(stored.getString("manual_mode", ""))
                ? stored.getString("manual_result", "") : "";
    }
    private void saveManualResult(String key, String value) {
        if (!scheduled || !session.equals(activeSession)
                || !key.equals(new AppPrefs(this).connectionKey())) return;
        getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                .putString("manual_session", session).putString("manual_mode", key)
                .putString("manual_result", value).apply();
    }
    private static boolean start(Context context, String action) {
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) {
            stop(context);
            return false;
        }
        if (!PrivilegeAccess.canRunInBackground(context)) return false;
        Intent intent = new Intent(context, FcmMonitorService.class);
        if (action != null) intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (RuntimeException error) {
            context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putString("error", "系统禁止启动后台监测：" + error.getMessage())
                    .putString("error_session", activeSession)
                    .putString("error_mode", prefs.connectionKey()).apply();
            return false;
        }
    }
    static void stop(Context context) {
        context.stopService(new Intent(context, FcmMonitorService.class));
    }
    static JSONObject lastSnapshot(Context context) {
        try {
            String raw = context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getString("json", "");
            return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception error) { return new JSONObject(); }
    }
    static String lastEvents(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getString("events", "");
    }
    static String lastEnvironmentEvents(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getString("environment_events", "");
    }
    static String lastRecoveryEvents(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getString("recovery_events", "");
    }
    static String lastError(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getString("error", "");
    }
    static String currentError(Context context) {
        SharedPreferences stored = context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE);
        return activeSession.equals(stored.getString("error_session", "no-session"))
                && new AppPrefs(context).connectionKey().equals(stored.getString("error_mode", ""))
                ? stored.getString("error", "") : "";
    }
    static long nextAttemptIn(Context context) {
        if (!isRunning()) return 0;
        long at = context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getLong("next_attempt_elapsed", 0);
        return Math.max(0, (at - SystemClock.elapsedRealtime() + 999) / 1000);
    }
    static long lastAttemptAt(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getLong("attempt_time", 0);
    }
    static long lastStartAt(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getLong("service_start_time", 0);
    }
    static String lastAttemptState(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getString("attempt_state", "pending");
    }

    @Override public void onCreate() {
        super.onCreate();
        ensureMonitorChannel(this);
        startForeground(NOTIFICATION_ID, notification());
        registerResumeTriggers();
    }
    private Notification notification() {
        String channel = monitorChannelId(this);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_bf_fcm)
                .setContentTitle("BerryFCM")
                .setContentText(notificationText())
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true).build();
    }

    private String notificationText() {
        if (!PrivilegeAccess.readyForUserAction(this)) return "正在恢复授权服务，暂未监控";
        AppPrefs prefs = new AppPrefs(this);
        if (!prefs.monitorEnabled()) return "应用守护已开启";
        long age = sampleAgeSeconds(this);
        JSONObject data = lastSnapshot(this);
        return MonitorStatus.resolve(true, prefs.monitorEnabled(), isRunning(), age,
                !currentError(this).isEmpty(), "gms".equals(data.optString("fcm_source")),
                data.optString("fcm_state")).label;
    }

    private void refreshNotification() {
        if (!scheduled) return;
        String text = notificationText();
        if (text.equals(lastNotificationText)) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        lastNotificationText = text;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        newestStartId = startId;
        AppPrefs prefs = new AppPrefs(this);
        if ((!prefs.monitorEnabled() && !prefs.guardEnabled()) || !PrivilegeAccess.canRunInBackground(this)) {
            stopIfIneligible(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());
        lastNotificationText = notificationText();
        boolean firstStart = !scheduled;
        if (firstStart) {
            activeSession = session;
            getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putLong("service_start_time", System.currentTimeMillis())
                    .putLong("next_attempt_elapsed", 0)
                    .putLong("attempt_time", 0)
                    .putString("attempt_state", "pending").apply();
            scheduled = true;
        }
        // Restart a terminated periodic future on a subsequent eligible start,
        // not just on initial Service creation.
        if (periodic == null || periodic.isCancelled() || periodic.isDone())
            periodic = worker.scheduleWithFixedDelay(() -> runSafe(false), 0, 15, TimeUnit.SECONDS);
        String action = intent == null ? null : intent.getAction();
        if (CHECK.equals(action) && !firstStart) queueCheck(true);
        if (RECONNECT.equals(action) && reconnectQueued.compareAndSet(false, true)) {
            final String requestedKey = prefs.connectionKey();
            final String requestedMode = prefs.mode();
            saveManualResult(requestedKey, "正在读取 GMS 并检查重连条件…");
            try {
                worker.execute(() -> {
                    try { manualReconnectInternal(requestedKey, requestedMode); }
                    finally {
                        String pending = manualReconnectResult(this);
                        if (pending.startsWith("正在读取"))
                            saveManualResult(requestedKey, "本次重连已结束，未确认执行结果");
                        reconnectQueued.set(false);
                    }
                });
            } catch (RejectedExecutionException error) {
                reconnectQueued.set(false);
                saveManualResult(requestedKey, "未执行：后台服务正在停止");
            }
        }
        return START_STICKY;
    }

    // Lifecycle changes are serialized on Android's main thread. A delayed
    // stop from an old worker must not kill a newer user start.
    private void stopIfIneligible(int startId) {
        AppPrefs prefs = new AppPrefs(this);
        if (startId != newestStartId || (scheduled
                && (prefs.monitorEnabled() || prefs.guardEnabled())
                && PrivilegeAccess.canRunInBackground(this))) return;
        if (!stopSelfResult(startId)) return;
        scheduled = false;
        if (session.equals(activeSession)) activeSession = "";
        if (periodic != null) periodic.cancel(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void registerResumeTriggers() {
        try {
            networkManager = getSystemService(ConnectivityManager.class);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { onNetworkChange(); }
                @Override public void onLost(Network network) { onNetworkChange(); }
                private String lastTransport = "";
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    String transport = transportName(caps);
                    if (!transport.equals(lastTransport)) {
                        lastTransport = transport;
                        onNetworkChange();
                    }
                }
            };
            if (networkManager != null) networkManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) { networkCallback = null; }
        try {
            resumeReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    PowerManager power = getSystemService(PowerManager.class);
                    if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())
                            && power != null && power.isDeviceIdleMode()) return;
                    queueCheck(false);
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(resumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(resumeReceiver, filter);
        } catch (RuntimeException ignored) { resumeReceiver = null; }
    }

    private void onNetworkChange() {
        networkSignal = SystemClock.elapsedRealtime();
        queueCheck(false);
    }

    private void queueCheck(boolean manual) {
        if (!scheduled || !checkQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    if (manual || SystemClock.elapsedRealtime() - lastCheckElapsed >= 5000)
                        runSafe(manual);
                } finally { checkQueued.set(false); }
            });
        } catch (RejectedExecutionException ignored) { checkQueued.set(false); }
    }

    private void runSafe(boolean requested) {
        if (Thread.currentThread().isInterrupted() || !scheduled || !session.equals(activeSession)) return;
        lastCheckElapsed = SystemClock.elapsedRealtime();
        PowerManager.WakeLock lock = null;
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null) {
                lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BerryFCM:sample");
                lock.setReferenceCounted(false);
                lock.acquire(90000L);
            }
        } catch (RuntimeException ignored) { lock = null; }
        try { runOnce(requested); }
        catch (RuntimeException error) {
            if (scheduled && session.equals(activeSession)) recordFailure(error);
        }
        finally {
            try { if (lock != null && lock.isHeld()) lock.release(); }
            catch (RuntimeException error) { android.util.Log.w("BerryFCM", "Sample wake lock release failed", error); }
            // A vendor notification exception must not escape the scheduled
            // task and permanently cancel monitoring. Display is optional.
            try { if (scheduled && session.equals(activeSession)) refreshNotification(); }
            catch (RuntimeException error) { android.util.Log.w("BerryFCM", "Monitor notification refresh failed", error); }
        }
    }

    private void recordFailure(Exception error) {
        failureStreak = Math.min(failureStreak + 1, 4);
        long backoff = 15000L << (failureStreak - 1);
        nextAttemptElapsed = SystemClock.elapsedRealtime() + backoff;
        getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                .putString("error", safeError(error))
                .putString("error_session", session)
                .putString("error_mode", new AppPrefs(this).connectionKey())
                .putString("attempt_state", "failed")
                .putLong("next_attempt_elapsed", nextAttemptElapsed).apply();
    }

    static String describeNativeReason(String value) {
        switch (value) {
            case "permission_denied": return "当前 Shell 无权读取 GMS 原生状态";
            case "timeout": return "GMS 状态读取超时";
            case "service_unavailable": return "GMS FCM 服务未返回状态";
            case "gms_stopped": return "Google Play 服务处于已停止状态";
            case "empty": return "GMS 返回空数据";
            case "dump_failed": return "GMS 状态读取失败";
            case "none": return "";
            default: return "未识别到原生状态";
        }
    }
    private void runOnce(boolean requested) {
        AppPrefs p = new AppPrefs(this);
        if ((!p.monitorEnabled() && !p.guardEnabled()) || !PrivilegeAccess.canRunInBackground(this)) {
            main.post(() -> stopIfIneligible(newestStartId));
            return;
        }
        final String attemptKey = p.connectionKey();
        boolean modeChanged = !attemptKey.equals(lastAttemptKey);
        if (p.monitorEnabled() && (requested || modeChanged || SystemClock.elapsedRealtime() >= nextAttemptElapsed)) {
            lastAttemptKey = attemptKey;
            getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putLong("attempt_time", System.currentTimeMillis())
                    .putString("attempt_state", "running")
                    .putLong("next_attempt_elapsed", 0).apply();
            try {
                String mode = p.mode();
                String key = p.connectionKey();
                if (!installed || !key.equals(installedMode)) {
                    installed = false;
                    installScript(p, mode);
                    installedMode = key;
                    installed = true;
                }
                if (!p.monitorEnabled() || !key.equals(p.connectionKey())) return;
                String prefix = monitorPrefix(p, mode);
                String result = execute(p, mode, prefix + "monitor >/dev/null 2>&1 || exit 9; "
                        + "echo BERRY_JSON_BEGIN; " + prefix + "snapshot; "
                        + "echo BERRY_EVENTS_BEGIN; " + prefix + "events; "
                        + "echo BERRY_RECOVERY_BEGIN; " + prefix + "recovery-events");
                int jsonStart = result.indexOf("BERRY_JSON_BEGIN\n");
                int eventsStart = result.indexOf("\nBERRY_EVENTS_BEGIN");
                int recoveryStart = result.indexOf("\nBERRY_RECOVERY_BEGIN", eventsStart);
                if (jsonStart < 0 || eventsStart <= jsonStart || recoveryStart <= eventsStart)
                    throw new IllegalStateException("监测脚本未返回完整状态");
                String json = result.substring(jsonStart + "BERRY_JSON_BEGIN\n".length(), eventsStart).trim();
                JSONObject parsed = new JSONObject(json);
                if (!parsed.has("fcm_source") || !parsed.has("fcm_native_reason"))
                    throw new IllegalStateException("监测脚本版本不匹配");
                String events = result.substring(eventsStart + "\nBERRY_EVENTS_BEGIN".length(), recoveryStart).trim();
                String recovery = result.substring(recoveryStart + "\nBERRY_RECOVERY_BEGIN".length()).trim();
                if (!scheduled || !session.equals(activeSession) || !p.monitorEnabled()
                        || !key.equals(p.connectionKey())) return;
                recordEnvironment(parsed);
                json = parsed.toString();
                failureStreak = 0;
                nextAttemptElapsed = 0;
                getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                        .putString("sample_session", session)
                        .putString("sample_mode", key)
                        .putLong("sample_elapsed", SystemClock.elapsedRealtime())
                        .putLong("next_attempt_elapsed", 0)
                        .putString("json", json).putString("events", events).putString("recovery_events", recovery)
                        .remove("error").putString("attempt_state", "success").apply();
            } catch (Exception error) {
                if (!Thread.currentThread().isInterrupted() && attemptKey.equals(p.connectionKey())
                        && scheduled && session.equals(activeSession) && p.monitorEnabled()) recordFailure(error);
                installed = false;
            }
        }
        long now = SystemClock.elapsedRealtime();
        String guardKey = p.connectionKey();
        java.util.Set<String> guardTargets = p.guardTargets();
        if (!p.guardEnabled()) {
            lastGuardAt = 0;
        } else if (!guardKey.equals(lastGuardKey) || !guardTargets.equals(lastGuardTargets)
                || lastGuardAt == 0 || now < lastGuardAt
                || now - lastGuardAt >= TimeUnit.MINUTES.toMillis(5)) {
            lastGuardAt = now;
            lastGuardKey = guardKey;
            lastGuardTargets = new java.util.HashSet<>(guardTargets);
            try {
                StringBuilder reports = new StringBuilder();
                String mode = p.mode();
                for (String command : GuardOps.commandBatches(guardTargets, true)) {
                    if (!scheduled || !p.guardEnabled() || !guardKey.equals(p.connectionKey())
                            || !guardTargets.equals(p.guardTargets())) return;
                    reports.append(execute(p, mode, command)).append("\n");
                }
                if (!scheduled || !p.guardEnabled() || !guardKey.equals(p.connectionKey())
                        || !guardTargets.equals(p.guardTargets())) return;
                String report = reports.toString().trim();
                if (report.contains("|restored")) {
                    report += "\n已修复应用，已请求 GMS 心跳（非连接成功证明）：\n"
                            + execute(p, mode, GuardOps.heartbeatCommand());
                }
                if (!scheduled || !p.guardEnabled() || !guardKey.equals(p.connectionKey())
                        || !guardTargets.equals(p.guardTargets())) return;
                getSharedPreferences("berry_fcm_guard_report", MODE_PRIVATE).edit()
                        .putString("report", report).putLong("last_run", System.currentTimeMillis()).apply();
            } catch (Exception error) {
                if (scheduled && session.equals(activeSession) && p.guardEnabled()
                        && attemptKey.equals(p.connectionKey()) && !Thread.currentThread().isInterrupted())
                    getSharedPreferences("berry_fcm_guard_report", MODE_PRIVATE).edit()
                            .putString("report", "守护检查失败：" + safeError(error))
                            .putLong("last_run", System.currentTimeMillis()).apply();
            }
        }
    }

    private void manualReconnectInternal(String key, String mode) {
        AppPrefs p = new AppPrefs(this);
        if (!scheduled || !session.equals(activeSession) || !p.monitorEnabled()
                || !key.equals(p.connectionKey()) || !mode.equals(p.mode())
                || !PrivilegeAccess.canRunInBackground(this)) {
            saveManualResult(key, "未执行：监控已关闭或核心授权暂不可用");
            return;
        }
        try {
            if (!installed || !key.equals(installedMode)) {
                installScript(p, mode);
                installed = true;
                installedMode = key;
            }
            if (!scheduled || !p.monitorEnabled() || !key.equals(p.connectionKey())) {
                saveManualResult(key, "未执行：监控或授权方式已变化");
                return;
            }
            String prefix = monitorPrefix(p, mode);
            String result = execute(p, mode, prefix + "reconnect >/dev/null || exit 9; "
                    + "echo BERRY_MANUAL_RESULT; " + prefix + "snapshot");
            int offset = result.indexOf("BERRY_MANUAL_RESULT\n");
            if (offset < 0) throw new IllegalStateException("重连脚本未返回结果");
            JSONObject reply = new JSONObject(result.substring(offset + "BERRY_MANUAL_RESULT\n".length()).trim());
            String decision = reply.optString("fcm_recovery_decision");
            String message;
            switch (decision) {
                case "requested": message = "已尝试恢复，等待连接确认"; break;
                case "request_failed": message = "广播请求失败，请查看恢复记录中的 UID 和错误"; break;
                case "cooldown": message = "未发送广播：距上次请求不足60秒"; break;
                case "gms_stopped_unknown": message = "未发送广播：GMS 停止状态未通过检查"; break;
                case "network_gate": message = "未发送广播：当前无活动网络"; break;
                case "not_disconnected": message = "未发送广播：" + ("connected".equals(reply.optString("fcm_state"))
                        ? "GMS 已连接" : "GMS 当前状态无法确认"); break;
                default: message = "重连结果未确认，见恢复记录"; break;
            }
            saveManualResult(key, message);
            if (key.equals(p.connectionKey())) {
                long checkStarted = SystemClock.elapsedRealtime();
                runSafe(true);
                JSONObject latest = lastSnapshot(this);
                long sampleElapsed = getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getLong("sample_elapsed", -1);
                if (sampleElapsed >= checkStarted && key.equals(p.connectionKey()) && "gms".equals(latest.optString("fcm_source"))
                        && "connected".equals(latest.optString("fcm_state"))
                        && sampleAgeSeconds(this) >= 0 && sampleAgeSeconds(this) <= 90
                        && currentError(this).isEmpty()) {
                    saveManualResult(key, message + "；随后检查 GMS 已连接");
                }
            }
        } catch (Exception error) {
            saveManualResult(key, "手动重连执行异常：" + safeError(error));
            if (scheduled && session.equals(activeSession) && p.monitorEnabled() && key.equals(p.connectionKey()))
                getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                        .putString("error", "手动重连请求失败：" + safeError(error))
                        .putString("error_session", session)
                        .putString("error_mode", key).apply();
        }
    }

    private static String transportName(NetworkCapabilities caps) {
        if (caps == null) return "unknown";
        StringBuilder names = new StringBuilder();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names.append("VPN+");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names.append("WiFi+");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names.append("Cell+");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names.append("Ethernet+");
        return names.length() == 0 ? "other" : names.substring(0, names.length() - 1);
    }

    // Sample context, not GMS route: do not log SSIDs, addresses, or tokens.
    // Environment changes are event-driven by the existing sample and add
    // no probes or privileged commands.
    private void recordEnvironment(JSONObject snapshot) throws org.json.JSONException {
        String network = "unknown", validated = "unknown";
        String screen = "unknown", idle = "unknown", powerSave = "unknown", gmsExempt = "unknown";
        try {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            if (manager != null) {
                Network active = manager.getActiveNetwork();
                if (active == null) network = "none";
                else {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(active);
                    network = transportName(caps);
                    if (caps != null) validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "yes" : "no";
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null) {
                screen = power.isInteractive() ? "on" : "off";
                idle = power.isDeviceIdleMode() ? "yes" : "no";
                powerSave = power.isPowerSaveMode() ? "yes" : "no";
                gmsExempt = power.isIgnoringBatteryOptimizations(GuardOps.GMS) ? "yes" : "no";
            }
        } catch (RuntimeException ignored) { }
        JSONObject env = new JSONObject();
        env.put("network", network);
        env.put("android_validated", validated);
        env.put("screen", screen);
        env.put("device_idle", idle);
        env.put("power_save", powerSave);
        env.put("gms_doze_exempt", gmsExempt);
        snapshot.put("app_environment", env);
        String state = snapshot.optString("fcm_state", "unknown");
        String description = "FCM=" + state + "; AppNetwork=" + network
                + "; AndroidValidated=" + validated + "; screen=" + screen
                + "; idle=" + idle + "; powerSave=" + powerSave + "; GMSDozeExempt=" + gmsExempt;
        SharedPreferences stored = getSharedPreferences(SNAPSHOT, MODE_PRIVATE);
        if (description.equals(stored.getString("environment_key", ""))
                && session.equals(stored.getString("environment_session", ""))) return;
        String event = (System.currentTimeMillis() / 1000) + "|environment|" + description;
        String history = stored.getString("environment_events", "");
        String[] lines = history.split("\\n");
        StringBuilder bounded = new StringBuilder();
        for (int i = Math.max(0, lines.length - 79); i < lines.length; i++) {
            if (!lines[i].isEmpty()) bounded.append(lines[i]).append('\n');
        }
        bounded.append(event);
        stored.edit().putString("environment_key", description)
                .putString("environment_session", session)
                .putString("environment_events", bounded.toString()).apply();
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null ? "后台监测执行失败" : message;
    }

    private static String workDir(String mode) {
        if (AppPrefs.SHIZUKU.equals(mode)) return ShizukuShell.authorizedUid() == 0
                ? "/data/local/tmp/berry_fcm_shizuku_root" : "/data/local/tmp/berry_fcm_apk";
        return AppPrefs.ROOT.equals(mode) ? "/data/adb/berry_fcm_apk" : "/data/local/tmp/berry_fcm_apk";
    }
    // This is the app's default network, not proof of GMS's route or reachability.
    // It is used only to defer automatic intervention during a network change.
    private String recoveryGate() {
        try {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            if (manager == null) return "unavailable";
            Network current = manager.getActiveNetwork();
            long now = SystemClock.elapsedRealtime();
            long signal = networkSignal;
            if (signal != consumedNetworkSignal) {
                consumedNetworkSignal = signal;
                networkChangedAt = now;
            }
            if (current == null) {
                observedNetwork = null;
                networkChangedAt = now;
                return "offline";
            }
            if (!current.equals(observedNetwork)) {
                observedNetwork = current;
                networkChangedAt = now;
            }
            return now - networkChangedAt >= 45000 ? "ready" : "settling";
        } catch (RuntimeException error) {
            return "unavailable";
        }
    }
    private String monitorPrefix(AppPrefs prefs, String mode) {
        String dir = workDir(mode);
        return "BERRY_FCM_AUTO=" + (prefs.autoReconnect() ? "1" : "0")
                + " BERRY_AUTO_GATE=" + recoveryGate()
                + " BERRY_FCM_DATA=" + dir
                + (AppPrefs.ROOT.equals(mode) ? "" : " BERRY_FCM_ADB_SHELL=1")
                + " /system/bin/sh " + dir + "/fcm-monitor.sh ";
    }
    private void installScript(AppPrefs prefs, String mode) throws Exception {
        byte[] script;
        try (InputStream input = getAssets().open("fcm-monitor.sh");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) out.write(buffer, 0, read);
            script = out.toByteArray();
        }
        String dir = workDir(mode);
        String encoded = Base64.encodeToString(script, Base64.NO_WRAP);
        String temp = dir + "/fcm-monitor.sh.new";
        String result = execute(prefs, mode, "umask 077; mkdir -p " + dir
                + " && printf '%s' '" + encoded + "' | base64 -d > " + temp
                + " && /system/bin/sh -n " + temp + " && chmod 700 " + temp
                + " && mv -f " + temp + " " + dir + "/fcm-monitor.sh"
                + " && echo 1 > " + dir + "/enabled && echo BERRY_INSTALL_OK");
        if (!result.trim().endsWith("BERRY_INSTALL_OK"))
            throw new IllegalStateException("监测脚本安装未通过确认");
    }

    private String execute(AppPrefs prefs, String mode, String command) throws Exception {
        if (AppPrefs.ROOT.equals(mode)) {
            try (PrivilegeShell shell = new RootShell(this)) {
                String result = shell.execute(command);
                if (mode.equals(prefs.mode())) prefs.shellVerified("uid=0(root)");
                return result;
            } catch (Exception error) {
                if (mode.equals(prefs.mode())) prefs.shellFailed(error.getMessage());
                throw error;
            }
        }
        if (AppPrefs.SHIZUKU.equals(mode)) {
            try (PrivilegeShell shell = new ShizukuShell(this)) {
                int uid = ShizukuShell.authorizedUid();
                if (uid < 0) throw new IllegalStateException(ShizukuShell.readiness());
                String result = shell.execute(command);
                if (mode.equals(prefs.mode())) prefs.shellVerified(uid == 0 ? "uid=0(root)" : "uid=2000(shell)");
                return result;
            } catch (Exception error) {
                if (mode.equals(prefs.mode())) prefs.shellFailed(error.getMessage());
                throw error;
            }
        }
        if (!AppPrefs.WIRELESS.equals(mode) && !AppPrefs.STATIC.equals(mode))
            throw new IllegalStateException("请先在授权中心选择 Root 或 ADB");
        try {
            String result;
            if (AppPrefs.WIRELESS.equals(mode)) {
                result = PrivKitShell.execute(this, command);
            } else {
                if (adb == null) adb = AdbConnectionManager.get(this);
                result = adb.executeOnce(this, mode, prefs.host(), prefs.staticPort(), command);
            }
            if (mode.equals(prefs.mode())) prefs.shellVerified("uid=2000(shell)");
            return result;
        } catch (Exception error) {
            if (mode.equals(prefs.mode())) prefs.shellFailed(error.getMessage());
            throw error;
        }
    }
    @Override public void onDestroy() {
        scheduled = false;
        if (session.equals(activeSession)) activeSession = "";
        if (networkManager != null && networkCallback != null) {
            try { networkManager.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
        }
        if (resumeReceiver != null) {
            try { unregisterReceiver(resumeReceiver); }
            catch (RuntimeException ignored) { }
        }
        main.removeCallbacksAndMessages(null);
        if (periodic != null) periodic.cancel(true);
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
