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
import android.util.Base64;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Background FCM sampler. Android owns this foreground service; Root or an
 * explicitly paired ADB shell owns the privileged GMS socket observations.
 * WRITE_SECURE_SETTINGS alone cannot inspect another app's TCP sockets.
 */
public final class FcmMonitorService extends Service {
    private static final String SNAPSHOT = "berry_fcm_monitor_snapshot";
    private static final String CHANNEL = "berry_fcm_monitor_active";
    private static final String QUIET_CHANNEL = "berry_fcm_monitor_quiet";
    private static final int NOTIFICATION_ID = 7212;
    private static final int INTERVAL_SECONDS = 15;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private boolean started;
    private boolean installed;
    private String installedMode;
    private AdbConnectionManager adb;
    private boolean adbSessionReady;

    static void start(Context context) {
        Intent intent = new Intent(context, FcmMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException error) {
            context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putString("error", "系统阻止启动后台监控：" + error.getMessage()).apply();
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, FcmMonitorService.class));
    }

    static JSONObject lastSnapshot(Context context) {
        try {
            String raw = context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE)
                    .getString("json", "");
            return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    static String lastEvents(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE)
                .getString("events", "");
    }

    static String lastError(Context context) {
        return context.getSharedPreferences(SNAPSHOT, MODE_PRIVATE)
                .getString("error", "");
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel active = new NotificationChannel(CHANNEL,
                "FCM 后台监测", NotificationManager.IMPORTANCE_LOW);
        active.setDescription("显示后台连接监测的运行状态");
        manager.createNotificationChannel(active);
        NotificationChannel quiet = new NotificationChannel(QUIET_CHANNEL,
                "FCM 静音监测", NotificationManager.IMPORTANCE_MIN);
        quiet.setDescription("静音并最小化通知，不会隐藏前台服务进程");
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
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("BerryFCM 后台监测")
                .setContentText("监测 GMS FCM Socket · 采样目标每 15 秒")
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!new AppPrefs(this).monitorEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());
        if (!started) {
            started = true;
            worker.scheduleWithFixedDelay(this::sampleSafely, 0,
                    INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private void sampleSafely() {
        if (!new AppPrefs(this).monitorEnabled()) {
            stopSelf();
            return;
        }
        try {
            AppPrefs prefs = new AppPrefs(this);
            String mode = prefs.mode();
            if (!(AppPrefs.ROOT.equals(mode) || AppPrefs.WIRELESS.equals(mode)
                    || AppPrefs.STATIC.equals(mode))) {
                throw new IllegalStateException("当前仅有应用权限；需要 Root 或正在运行的 ADB Shell 才能读取 GMS Socket");
            }
            if (!installed || !mode.equals(installedMode)) {
                installScript(prefs, mode);
                installed = true;
                installedMode = mode;
            }
            String dir = workingDirectory(mode);
            String prefix = "BERRY_FCM_DATA=" + dir
                    + (AppPrefs.ROOT.equals(mode) ? "" : " BERRY_FCM_ADB_SHELL=1")
                    + " /system/bin/sh " + dir + "/fcm-monitor.sh ";
            String command = prefix + "monitor >/dev/null 2>&1 || exit 9; "
                    + "echo BERRY_JSON_BEGIN; " + prefix + "snapshot; "
                    + "echo BERRY_EVENTS_BEGIN; " + prefix + "events";
            String output = execute(prefs, mode, command);
            int jsonAt = output.indexOf("BERRY_JSON_BEGIN\n");
            int eventsAt = output.indexOf("\nBERRY_EVENTS_BEGIN");
            if (jsonAt < 0 || eventsAt < 0 || eventsAt <= jsonAt) {
                throw new IllegalStateException("监控采样未返回完整状态");
            }
            String json = output.substring(jsonAt + "BERRY_JSON_BEGIN\n".length(), eventsAt).trim();
            new JSONObject(json);
            String events = output.substring(eventsAt + "\nBERRY_EVENTS_BEGIN".length()).trim();
            getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putString("json", json).putString("events", events)
                    .remove("error").apply();
        } catch (Throwable error) {
            // Failed observations are never represented as disconnected.
            getSharedPreferences(SNAPSHOT, MODE_PRIVATE).edit()
                    .putString("error", error.getMessage() == null
                            ? "后台监测失败" : error.getMessage()).apply();
            installed = false;
            adbSessionReady = false;
        }
    }

    private static String workingDirectory(String mode) {
        return AppPrefs.ROOT.equals(mode)
                ? "/data/adb/berry_fcm_apk"
                : "/data/local/tmp/berry_fcm_apk";
    }

    private void installScript(AppPrefs prefs, String mode) throws Exception {
        byte[] data;
        try (InputStream input = getAssets().open("fcm-monitor.sh")) {
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) >= 0) result.write(buffer, 0, n);
            data = result.toByteArray();
        }
        String dir = workingDirectory(mode);
        String encoded = Base64.encodeToString(data, Base64.NO_WRAP);
        String command = "umask 077; mkdir -p " + dir
                + " && printf '%s' '" + encoded + "' | base64 -d > " + dir
                + "/fcm-monitor.sh && chmod 700 " + dir + "/fcm-monitor.sh";
        execute(prefs, mode, command);
        execute(prefs, mode, "echo 1 > " + dir + "/enabled");
    }

    private String execute(AppPrefs prefs, String mode, String command) throws Exception {
        if (AppPrefs.ROOT.equals(mode)) {
            File output = File.createTempFile("berry-fcm-", ".log", getCacheDir());
            try {
                Process p = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true).redirectOutput(output).start();
                if (!p.waitFor(25, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("Root Shell 执行超过 25 秒");
                }
                byte[] bytes = Files.readAllBytes(output.toPath());
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (p.exitValue() != 0)
                    throw new IllegalStateException("Root Shell 退出码 "
                            + p.exitValue() + "：" + text.trim());
                return text;
            } finally {
                output.delete();
            }
        }
        if (adb == null) adb = AdbConnectionManager.get(this);
        if (!AppPrefs.WIRELESS.equals(mode) && !AppPrefs.STATIC.equals(mode))
            throw new IllegalStateException("当前模式没有可用 Shell");
        if (!adbSessionReady) {
            boolean ok = AppPrefs.WIRELESS.equals(mode)
                    ? adb.autoConnectLocal(this)
                    : adb.connectStatic(prefs.host(), prefs.staticPort());
            if (!ok) throw new IllegalStateException("ADB Shell 未连接：请检查无线调试或静态 ADB 端口");
            adbSessionReady = true;
        }
        try {
            return adb.shell(command);
        } catch (Exception error) {
            adbSessionReady = false;
            adb.disconnectSession();
            throw error;
        }
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        if (adb != null) adb.disconnectSession();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
