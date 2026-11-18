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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PairingGate gate = new PairingGate();
    private Future<?> task;
    private int manualPairPort;
    private static volatile boolean active;
    static boolean isPairingActive() { return active; }

    static void begin(Context context, int manualPort) {
        Intent i = new Intent(context, AdbPairingService.class).setAction(ACTION_BEGIN)
                .putExtra("manual_port", manualPort);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }
    static void stop(Context context) {
        context.startService(new Intent(context, AdbPairingService.class).setAction(ACTION_STOP));
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
            if (!gate.ready(generation, port)) return START_NOT_STICKY;
            if (!code.matches("[0-9]{6}")) {
                publish("配对码必须是六位数字，请重新输入", true, port);
                return START_NOT_STICKY;
            }
            if (!gate.submit(generation, port)) return START_NOT_STICKY;
            publish("正在配对…", false, 0);
            final boolean manual = manualPairPort > 0;
            // Cancel any rediscovery in progress before starting the handshake.
            if (task != null) task.cancel(true);
            task = worker.submit(() -> pair(generation, port, code, manual));
            return START_NOT_STICKY;
        }
        if (!ACTION_BEGIN.equals(intent.getAction())) return START_NOT_STICKY;
        if (gate.isPairing()) return START_NOT_STICKY;
        cancel();
        int generation = gate.begin();
        active = true;
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
                // Manual port is an explicit fallback, not an auto-discovered endpoint.
                Thread.sleep(manual > 0 ? 1000L : 500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                if (!gate.searching(generation)) return;
                if (previous > 0) gate.lost(generation, previous);
                previous = 0;
                main.post(() -> {
                    if (gate.searching(generation) && !gate.hasPort())
                        publish("请打开系统无线调试中的“使用配对码配对设备”", false, 0);
                });
                try { Thread.sleep(500L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void cancel() {
        active = false;
        gate.cancel();
        if (task != null) task.cancel(true);
        task = null;
    }

    private void pair(int generation, int selectedPort, String code, boolean manual) {
        AppPrefs prefs = new AppPrefs(this);
        try {
            if (!gate.pairing(generation)) return;
            // Passing a numeric port forces upstream to use loopback. Null keeps
            // the LAN address resolved by upstream for the normal automatic flow.
            PrivKitShell.pair(this, manual ? selectedPort : null, code);
        } catch (Exception error) {
            String message = PrivKitShell.errorChain(error);
            prefs.shellFailed(message);
            finish(generation, "配对失败：" + message);
            return;
        }
        if (!gate.pairing(generation)) return;
        prefs.wirelessConnectPort(0);
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
            prefs.monitorEnabled(true);
            boolean requested = FcmMonitorService.start(this);
            finish(generation, requested
                    ? "授权成功，正在开启 FCM 监控"
                    : "授权成功，请返回应用开启监控");
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
        gate.cancel();
        updateStatus(message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).notify(NOTIFICATION,
                new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_bf_auth)
                        .setContentTitle("BerryFCM ADB 授权")
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setAutoCancel(true).build());
        stopSelf();
    }

    private void updateStatus(String text) {
        new AppPrefs(this).pairingStatus(text);
    }

    private void publish(String message, boolean withInput, int selectedPort) {
        updateStatus(message);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_bf_auth)
                .setContentTitle("BerryFCM · 无线 ADB 配对")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setOngoing(true);
        if (withInput) {
            Intent reply = new Intent(this, AdbPairingService.class).setAction(ACTION_REPLY)
                    .putExtra("port", selectedPort)
                    .putExtra("generation", gate.generation())
                    .setData(android.net.Uri.parse("berryfcm://pair/" + gate.generation() + "/" + selectedPort));
            PendingIntent pending = PendingIntent.getForegroundService(this, 10, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            Notification.Action input = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_edit,
                    "输入六位配对码", pending)
                    .addRemoteInput(new RemoteInput.Builder(KEY_CODE)
                            .setLabel("六位配对码").build())
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
