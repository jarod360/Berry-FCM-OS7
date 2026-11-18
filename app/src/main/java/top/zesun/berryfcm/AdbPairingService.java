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
            if (!code.matches("[0-9]{6}") || !gate.submit(generation, port))
                return START_NOT_STICKY;
            publish("正在配对，随后验证 ADB 认证和独立服务", false, 0);
            // The discovery worker exits on submit before this queued operation.
            task = worker.submit(() -> pair(generation, port, code));
            return START_NOT_STICKY;
        }
        if (!ACTION_BEGIN.equals(intent.getAction())) return START_NOT_STICKY;
        if (gate.isPairing()) return START_NOT_STICKY;
        cancel();
        int generation = gate.begin();
        active = true;
        int manual = intent.getIntExtra("manual_port", 0);
        publish("等待系统配对服务：请打开“使用配对码配对设备”", false, 0);
        task = worker.submit(() -> discover(generation, manual));
        return START_NOT_STICKY;
    }

    private void discover(int generation, int manual) {
        // Match Priv Kit's own UI: discover first, periodically check that
        // the endpoint remains available, disable input again when it disappears.
        while (gate.searching(generation) && !Thread.currentThread().isInterrupted()) {
            try {
                int found = manual > 0 ? manual : PrivKitShell.discoverPairingPort();
                if (!gate.searching(generation)) return;
                if (!localPortListening(found)) {
                    manual = 0;
                    Thread.sleep(500L);
                    continue;
                }
                if (!gate.found(generation, found)) return;
                main.post(() -> {
                    if (gate.ready(generation, found))
                        publish("已发现本机配对服务，输入系统显示的六位配对码", true, found);
                });
                while (gate.searching(generation) && localPortListening(found))
                    Thread.sleep(500L);
                if (!gate.searching(generation)) return;
                gate.lost(generation, found);
                main.post(() -> {
                    if (gate.searching(generation) && !gate.hasPort())
                        publish("配对服务已关闭，等待重新打开系统配对码页面", false, 0);
                });
                manual = 0;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                if (!gate.searching(generation)) return;
                main.post(() -> {
                    if (gate.searching(generation) && !gate.hasPort())
                        publish("正在搜索本机配对服务，请保持系统配对码页面打开", false, 0);
                });
                try { Thread.sleep(500L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    // The upstream Priv Kit UI uses a local bind probe, not a connection that
    // might consume the pairing service's handshake.
    private static boolean localPortListening(int port) {
        if (port <= 0 || port > 65535) return false;
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", port), 1);
            return false;
        } catch (java.io.IOException busy) { return true; }
    }

    private void cancel() {
        active = false;
        gate.cancel();
        if (task != null) task.cancel(true);
        task = null;
    }

    private void pair(int generation, int selectedPort, String code) {
        AppPrefs prefs = new AppPrefs(this);
        try {
            if (!gate.pairing(generation)) return;
            PrivKitShell.pair(this, selectedPort, code);
        } catch (Exception error) {
            String message = String.valueOf(error.getMessage());
            prefs.shellFailed(message);
            finish(generation, "配对失败：" + message);
            return;
        }
        if (!gate.pairing(generation)) return;
        prefs.wirelessConnectPort(0);
        main.post(() -> { if (gate.pairing(generation))
            publish("配对交换完成，正在验证真实认证并启动服务", false, 0); });
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
                    ? "配对成功，独立服务已运行，已请求启动监控"
                    : "配对成功，独立服务已运行；请返回应用启动监控");
        } catch (Exception error) {
            prefs.shellFailed(error.getMessage());
            finish(generation, "配对交换完成，独立服务未启动：" + error.getMessage());
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
