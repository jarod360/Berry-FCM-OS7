package top.zesun.berryfcm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shizuku-style pairing via a RemoteInput action while Developer Options is open.
 * No screen overlay, AccessibilityService or pairing code persisted to disk.
 */
public final class AdbPairingService extends Service {
    private static final String CHANNEL = "berryfcm_pairing";
    private static final String KEY_CODE = "pair_code";
    private static final String ACTION_BEGIN = "berryfcm.pair.begin";
    private static final String ACTION_REPLY = "berryfcm.pair.reply";
    private static final String ACTION_STOP = "berryfcm.pair.stop";
    private static final int NOTIFICATION = 7310;
    private static final long MANUAL_FALLBACK_DELAY_MS = 15000L;
    private static final long PAIR_INPUT_TIMEOUT_MS = 120000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PairingGate gate = new PairingGate();
    private Future<?> task;
    private int manualPairPort;
    private static volatile boolean active;
    private static volatile boolean codeSubmitted;
    static boolean isPairingActive() { return active; }
    static boolean isAwaitingInput() { return active && !codeSubmitted; }
    static boolean isCodeSubmitted() { return active && codeSubmitted; }

    static void begin(Context context, int manualPort) {
        Intent i = new Intent(context, AdbPairingService.class).setAction(ACTION_BEGIN)
                .putExtra("manual_port", manualPort);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }
    static void stop(Context context) {
        if (active) context.startService(new Intent(context, AdbPairingService.class).setAction(ACTION_STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "无线 ADB 配对", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("在无线调试页面通过通知栏输入六位配对码");
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            if (active) updateStatus("配对已取消，可以继续使用原有授权方式");
            cancel();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REPLY.equals(intent.getAction())) {
            int generation = intent.getIntExtra("generation", -1);
            int port = intent.getIntExtra("port", 0);
            android.os.Bundle reply = RemoteInput.getResultsFromIntent(intent);
            CharSequence value = reply == null ? null : reply.getCharSequence(KEY_CODE);
            String code = value == null ? "" : value.toString().trim();
            // Stale notifications, missing service and duplicate submissions
            // cannot initiate pairing. The port comes from discovery, not input.
            if (!active) { stopSelf(); return START_NOT_STICKY; }
            boolean fallback = port == 0 && intent.getBooleanExtra("manual_fallback", false);
            if (fallback) {
                if (!gate.searching(generation) || gate.hasPort()) return START_NOT_STICKY;
                String[] parts = code.split(":", -1);
                int entered = 0;
                try { if (parts.length == 2) entered = Integer.parseInt(parts[0].trim()); }
                catch (NumberFormatException ignored) { }
                if (entered < 1 || entered > 65535 || parts.length != 2 || !parts[1].trim().matches("[0-9]{6}")) {
                    publish("请输入系统配对端口:六位配对码", true, 0);
                    return START_NOT_STICKY;
                }
                if (!gate.submitManual(generation, entered)) return START_NOT_STICKY;
                port = entered;
                code = parts[1].trim();
            } else {
                if (!gate.ready(generation, port)) return START_NOT_STICKY;
                if (!code.matches("[0-9]{6}")) {
                    publish("配对码必须是六位数字，请重新输入", true, port);
                    return START_NOT_STICKY;
                }
                if (!gate.submit(generation, port)) return START_NOT_STICKY;
            }
            codeSubmitted = true;
            publish("正在验证配对码…", false, 0);
            final boolean manual = fallback || manualPairPort > 0;
            final int submittedPort = port;
            final String submittedCode = code;
            // Cancel any rediscovery in progress before starting the handshake.
            if (task != null) task.cancel(true);
            task = worker.submit(() -> pair(generation, submittedPort, submittedCode, manual));
            return START_NOT_STICKY;
        }
        if (!ACTION_BEGIN.equals(intent.getAction())) {
            if (!active) stopSelf();
            return START_NOT_STICKY;
        }
        if (gate.isPairing()) return START_NOT_STICKY;
        cancel();
        int generation = gate.begin();
        active = true;
        codeSubmitted = false;
        main.postDelayed(() -> {
            if (active && gate.searching(generation)) {
                complete("未收到配对码，已自动结束配对");
            }
        }, PAIR_INPUT_TIMEOUT_MS);
        int manual = intent.getIntExtra("manual_port", 0);
        manualPairPort = manual;
        publish("等待系统配对服务：请打开“使用配对码配对设备”", false, 0);
        task = worker.submit(() -> discover(generation, manual));
        return START_NOT_STICKY;
    }

    private void discover(int generation, int manual) {
        // Let Priv Kit validate the actual local endpoint (LAN or loopback).
        // A second loopback bind test rejects valid Android 17 LAN listeners.
        int previous = 0;
        long searchStart = android.os.SystemClock.elapsedRealtime();
        while (gate.searching(generation) && !Thread.currentThread().isInterrupted()) {
            try {
                int found = manual > 0 ? manual : PrivKitShell.discoverPairingPort();
                if (!gate.searching(generation)) return;
                if (found != previous) {
                    if (!gate.found(generation, found)) return;
                    previous = found;
                    main.post(() -> {
                        if (gate.ready(generation, found))
                            publish("请输入系统显示的六位配对码", true, found);
                    });
                }
                // The code is valid only while this pairing screen stays open.
                // No second mDNS discovery cycle: once a port is found, let
                // the existing notification accept its six-digit code.
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                if (!gate.searching(generation)) return;
                if (previous > 0) gate.lost(generation, previous);
                previous = 0;
                String failureText = PrivKitShell.errorChain(failure);
                if (failureText.contains("ADB service") && failureText.matches("(?s).*:[ ]?7(?:[ ]|$).*")) {
                    main.post(() -> { if (gate.searching(generation))
                        complete("Android 17 拒绝了本地网络 NSD 服务发现（错误 7）。请返回应用授权本地网络访问。"); });
                    return;
                }
                boolean fallback = android.os.SystemClock.elapsedRealtime() - searchStart >= MANUAL_FALLBACK_DELAY_MS;
                main.post(() -> {
                    if (gate.searching(generation) && !gate.hasPort())
                        publish(fallback ? "未自动发现端口，可在通知栏输入配对端口:六位配对码"
                                : "请打开系统无线调试中的“使用配对码配对设备”", fallback, 0);
                });
                try { Thread.sleep(500L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void cancel() {
        active = false;
        codeSubmitted = false;
        gate.cancel();
        if (task != null) task.cancel(true);
        task = null;
    }

    private void pair(int generation, int selectedPort, String code, boolean manual) {
        AppPrefs prefs = new AppPrefs(this);
        try {
            if (!gate.pairing(generation)) return;
            // Reuse the exact endpoint just discovered: the old automatic
            // path did a second mDNS lookup here and could lose the pairing
            // page or fail NSD even after a valid port had been found.
            PrivKitShell.pair(this, selectedPort, code);
        } catch (Exception error) {
            String message = PrivKitShell.errorChain(error);
            prefs.shellFailed(message);
            finish(generation, "配对失败：" + message);
            return;
        }
        if (!gate.pairing(generation)) return;
        prefs.wirelessConnectPort(0);
        prefs.servicePaused(AppPrefs.WIRELESS, false);
        main.post(() -> { if (gate.pairing(generation))
            publish("配对完成，正在启动服务…", false, 0); });
        try {
            // Finish the actual independent service handshake while the pairing
            // foreground service is still active. Do not claim success early.
            PrivKitShell.start(this);
            String identity = PrivKitShell.execute(this, "id").trim();
            if (!identity.matches("(?s)^uid=2000(?:\\D.*)?$"))
                throw new IllegalStateException("独立服务身份不匹配");
            if (!gate.pairing(generation)) return;
            prefs.activateMode(AppPrefs.WIRELESS, prefs.host(), prefs.staticPort(), identity);
            prefs.enableMonitorOnFirstAuthorization();
            // Pairing alone is not authorization: the live Binder and id
            // above must both succeed before enabling background tasks.
            boolean requested = (prefs.monitorEnabled() || prefs.guardEnabled()) && FcmMonitorService.start(this);
            finish(generation, requested
                    ? "授权成功，正在恢复已启用的后台任务"
                    : "授权成功，请返回应用");
        } catch (Exception error) {
            prefs.shellFailed(error.getMessage());
            finish(generation, "服务启动失败，请返回应用重试。\n" + PrivKitShell.errorChain(error));
        }
    }

    private void finish(int generation, String message) {
        main.post(() -> {
            if (!gate.pairing(generation)) return;
            complete(message);
        });
    }

    private void complete(String message) {
        active = false;
        codeSubmitted = false;
        gate.cancel();
        updateStatus(message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        PendingIntent open = PendingIntent.getActivity(this, 12, new Intent(this, AuthActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).notify(NOTIFICATION,
                new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_bf_auth)
                        .setContentTitle("BerryFCM ADB 授权")
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setContentIntent(open).setTimeoutAfter(15000L).setAutoCancel(true).build());
        stopSelf();
    }

    private void updateStatus(String text) {
        new AppPrefs(this).pairingStatus(text);
    }

    private void publish(String message, boolean withInput, int selectedPort) {
        updateStatus(message);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_bf_auth)
                .setColor(android.graphics.Color.WHITE)
                .setContentTitle("BerryFCM · 无线 ADB 配对")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setOngoing(true);
        if (withInput) {
            Intent reply = new Intent(this, AdbPairingService.class).setAction(ACTION_REPLY)
                    .putExtra("port", selectedPort)
                    .putExtra("generation", gate.generation())
                    .putExtra("manual_fallback", selectedPort == 0)
                    .setData(android.net.Uri.parse("berryfcm://pair/" + gate.generation() + "/" + selectedPort));
            // The pairing service is already foreground. A stale reply must never
            // create a new service that owes Android a startForeground call.
            PendingIntent pending = PendingIntent.getService(this, 10, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            Notification.Action input = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_edit,
                    selectedPort == 0 ? "输入端口和配对码" : "输入六位配对码", pending)
                    .addRemoteInput(new RemoteInput.Builder(KEY_CODE)
                            .setLabel(selectedPort == 0 ? "端口:六位配对码" : "六位配对码").build())
                    .build();
            b.addAction(input);
        }
        Intent cancel = new Intent(this, AdbPairingService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 11, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "取消配对", stop).build());
        startForeground(NOTIFICATION, b.build());
    }

    @Override public void onDestroy() {
        cancel();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
