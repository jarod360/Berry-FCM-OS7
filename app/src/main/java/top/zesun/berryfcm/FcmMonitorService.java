package top.zesun.berryfcm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private boolean scheduled, installed;
    private String installedMode;
    private AdbConnectionManager adb;
    private long lastGuardAt;

    static void start(Context context) { start(context, null); }
    static void checkNow(Context context) { start(context, CHECK); }
    static String manualReconnect(Context context) {
        start(context, RECONNECT);
        return "已提交重连请求；请等待后台事件记录，广播完成不代表 FCM 已恢复";
    }
    private static void start(Context context, String action) {
        Intent intent = new Intent(context, FcmMonitorService.class);
        if (action != null) intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException error) {
            context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putString("error", "系统禁止启动后台监测：" + error.getMessage()).apply();
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
    static String lastError(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).getString("error", "");
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
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel active = new NotificationChannel(CHANNEL,
                "FCM 后台监测", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(active);
        NotificationChannel quiet = new NotificationChannel(QUIET_CHANNEL,
                "FCM 静音监测", NotificationManager.IMPORTANCE_MIN);
        quiet.setSound(null, null);
        manager.createNotificationChannel(quiet);
        startForeground(NOTIFICATION_ID, notification());
    }
    private Notification notification() {
        String channel = new AppPrefs(this).quietMonitor() ? QUIET_CHANNEL : CHANNEL;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_bf_fcm)
                .setContentTitle("BerryFCM 后台守护")
                .setContentText("用户已启用的 FCM 监测与应用守护")
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        AppPrefs prefs = new AppPrefs(this);
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());
        if (!scheduled) {
            getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putLong("service_start_time", System.currentTimeMillis()).apply();
            scheduled = true;
            worker.scheduleWithFixedDelay(this::runOnce, 0, 15, TimeUnit.SECONDS);
        }
        String action = intent == null ? null : intent.getAction();
        if (CHECK.equals(action)) worker.execute(this::runOnce);
        if (RECONNECT.equals(action)) worker.execute(this::manualReconnectInternal);
        return START_STICKY;
    }

    private void runOnce() {
        AppPrefs p = new AppPrefs(this);
        if (!p.monitorEnabled() && !p.guardEnabled()) {
            stopSelf();
            return;
        }
        if (p.monitorEnabled()) {
            getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putLong("attempt_time", System.currentTimeMillis())
                    .putString("attempt_state", "running").apply();
            try {
                String mode = p.mode();
                if (!installed || !mode.equals(installedMode)) {
                    installScript(p, mode);
                    installedMode = mode;
                    installed = true;
                }
                String prefix = monitorPrefix(mode);
                String result = execute(p, mode, prefix + "monitor >/dev/null 2>&1 || exit 9; "
                        + "echo BERRY_JSON_BEGIN; " + prefix + "snapshot; "
                        + "echo BERRY_EVENTS_BEGIN; " + prefix + "events");
                int jsonStart = result.indexOf("BERRY_JSON_BEGIN\n");
                int eventsStart = result.indexOf("\nBERRY_EVENTS_BEGIN");
                if (jsonStart < 0 || eventsStart <= jsonStart)
                    throw new IllegalStateException("监测脚本未返回完整状态");
                String json = result.substring(jsonStart + "BERRY_JSON_BEGIN\n".length(), eventsStart).trim();
                new JSONObject(json);
                String events = result.substring(eventsStart + "\nBERRY_EVENTS_BEGIN".length()).trim();
                getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                        .putString("json", json).putString("events", events)
                        .remove("error").putString("attempt_state", "success").apply();
            } catch (Exception error) {
                getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                        .putString("error", safeError(error))
                        .putString("attempt_state", "failed").apply();
                installed = false;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (p.guardEnabled() && (lastGuardAt == 0 || now < lastGuardAt
                || now - lastGuardAt >= TimeUnit.MINUTES.toMillis(5))) {
            lastGuardAt = now;
            try {
                String report = execute(p, p.mode(), GuardOps.command(p.guardTargets(), true));
                if (report.contains("|restored")) {
                    report += "\n已修复应用，已请求 GMS 心跳（非连接成功证明）：\n"
                            + execute(p, p.mode(), GuardOps.heartbeatCommand());
                }
                getSharedPreferences("berry_fcm_guard_report", MODE_PRIVATE).edit()
                        .putString("report", report).putLong("last_run", System.currentTimeMillis()).apply();
            } catch (Exception error) {
                getSharedPreferences("berry_fcm_guard_report", MODE_PRIVATE).edit()
                        .putString("report", "守护检查未执行：" + safeError(error)).apply();
            }
        }
    }

    private void manualReconnectInternal() {
        try {
            AppPrefs p = new AppPrefs(this);
            if (!p.monitorEnabled()) throw new IllegalStateException("FCM 监控未启用");
            String mode = p.mode();
            if (!installed || !mode.equals(installedMode)) {
                installScript(p, mode);
                installed = true;
                installedMode = mode;
            }
            execute(p, mode, monitorPrefix(mode) + "reconnect");
            runOnce();
        } catch (Exception error) {
            getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putString("error", "手动重连请求失败：" + safeError(error)).apply();
        }
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null ? "后台监测执行失败" : message;
    }

    private static String workDir(String mode) {
        return AppPrefs.ROOT.equals(mode) ? "/data/adb/berry_fcm_apk" : "/data/local/tmp/berry_fcm_apk";
    }
    private static String monitorPrefix(String mode) {
        String dir = workDir(mode);
        return "BERRY_FCM_DATA=" + dir
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
        execute(prefs, mode, "umask 077; mkdir -p " + dir
                + " && printf '%s' '" + encoded + "' | base64 -d > " + dir
                + "/fcm-monitor.sh && chmod 700 " + dir + "/fcm-monitor.sh && echo 1 > " + dir + "/enabled");
    }

    private String execute(AppPrefs prefs, String mode, String command) throws Exception {
        if (AppPrefs.ROOT.equals(mode)) {
            File output = File.createTempFile("berryfcm-", ".txt", getCacheDir());
            try {
                Process process = new ProcessBuilder("su", "-c", "id -u; " + command)
                        .redirectErrorStream(true).redirectOutput(output).start();
                if (!process.waitFor(25, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("Root Shell 25 秒超时");
                }
                String raw = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
                int firstLine = raw.indexOf('\n');
                if (firstLine < 0 || !"0".equals(raw.substring(0, firstLine).trim())) {
                    prefs.shellFailed("Root UID 验证失败");
                    throw new IllegalStateException("未取得 Root Shell，请在 KernelSU/Magisk 中授权");
                }
                String text = raw.substring(firstLine + 1);
                if (process.exitValue() != 0)
                    throw new IllegalStateException("Root Shell 失败（退出码 "
                            + process.exitValue() + "）：" + text.trim());
                prefs.shellVerified("uid=0(root)");
                return text;
            } finally { output.delete(); }
        }
        if (!AppPrefs.WIRELESS.equals(mode) && !AppPrefs.STATIC.equals(mode))
            throw new IllegalStateException("请先在授权中心选择 Root 或 ADB");
        if (adb == null) adb = AdbConnectionManager.get(this);
        try {
            // The shared ADB manager may have been switched by the authorization
            // screen. Check the requested transport on every operation.
            boolean ok = AppPrefs.WIRELESS.equals(mode)
                    ? adb.autoConnectLocal(this)
                    : adb.connectStatic(prefs.host(), prefs.staticPort());
            if (!ok) throw new IllegalStateException("ADB Shell 未连接；请检查无线调试或 TCP 端口");
            if (!prefs.shellRecentlyVerified()) {
                String identity = adb.shell("id").trim();
                if (!identity.contains("uid=2000"))
                    throw new IllegalStateException("ADB Shell 身份异常：" + identity);
                prefs.shellVerified(identity);
            }
            String result = adb.shell(command);
            prefs.shellVerified(prefs.shellIdentity());
            return result;
        } catch (Exception error) {
            adb.disconnectSession();
            prefs.shellFailed(error.getMessage());
            throw error;
        }
    }
    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
