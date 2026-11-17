package top.zesun.berryfcm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
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
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int port;

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
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopDiscovery();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REPLY.equals(action)) {
            int selectedPort = intent.getIntExtra("port", port);
            CharSequence value = null;
            android.os.Bundle reply = RemoteInput.getResultsFromIntent(intent);
            if (reply != null) value = reply.getCharSequence(KEY_CODE);
            String code = value == null ? "" : value.toString().trim();
            if (!code.matches("[0-9]{6}") || selectedPort <= 0 || selectedPort > 65535) {
                publish("配对码应为 6 位数字；确认无线调试配对端口已发现", true, selectedPort);
                return START_REDELIVER_INTENT;
            }
            stopDiscovery();
            publish("配对中，正在验证 ADB 身份…", false, 0);
            final int p = selectedPort;
            worker.execute(() -> pair(p, code));
            return START_REDELIVER_INTENT;
        }
        port = intent == null ? 0 : intent.getIntExtra("manual_port", 0);
        publish(port > 0 ? "已指定配对端口 " + port + "；可直接从通知输入配对码"
                : "正在发现本机无线 ADB 配对端口…", port > 0, port);
        if (port <= 0) startDiscovery();
        return START_REDELIVER_INTENT;
    }

    private void startDiscovery() {
        if (discovery != null) return;
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String type) {}
                @Override public void onStartDiscoveryFailed(String type, int error) {
                    updateStatus("mDNS 发现失败（" + error + "），可返回授权页填写备用端口");
                }
                @Override public void onStopDiscoveryFailed(String type, int error) {}
                @Override public void onDiscoveryStopped(String type) {}
                @Override public void onServiceLost(NsdServiceInfo service) {}
                @Override public void onServiceFound(NsdServiceInfo service) {
                    try {
                        nsd.resolveService(service, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo service, int error) {
                                updateStatus("配对端口解析失败：" + error);
                            }
                            @Override public void onServiceResolved(NsdServiceInfo resolved) {
                                int found = resolved.getPort();
                                if (found > 0 && found <= 65535) {
                                    port = found;
                                    publish("已找到无线 ADB 配对端口 " + found + "，展开通知输入六位配对码", true, found);
                                }
                            }
                        });
                    } catch (RuntimeException error) {
                        updateStatus("解析配对端口失败：" + error.getMessage());
                    }
                }
            };
            nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException error) {
            updateStatus("无线调试发现不可用：" + error.getMessage());
        }
    }

    private void pair(int selectedPort, String code) {
        try {
            AdbConnectionManager adb = AdbConnectionManager.get(this);
            if (!adb.pairLocal(selectedPort, code))
                throw new IllegalStateException("配对未成功，检查配对码或端口");
            new AppPrefs(this).mode(AppPrefs.WIRELESS);
            String state;
            try {
                boolean connected = adb.autoConnectLocal(this);
                state = connected ? "配对成功，ADB Shell 已连接"
                        : "配对成功，但 ADB Shell 未连接：确认无线调试仍开启";
            } catch (Exception exception) {
                state = "配对成功；连接待验证：" + exception.getMessage();
            }
            complete(state);
        } catch (Exception error) {
            complete("配对失败：" + error.getMessage());
        }
    }

    private void complete(String message) {
        updateStatus(message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).notify(NOTIFICATION,
                new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
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
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("BerryFCM · 无线 ADB 配对")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setOngoing(true);
        if (withInput) {
            Intent reply = new Intent(this, AdbPairingService.class).setAction(ACTION_REPLY)
                    .putExtra("port", selectedPort);
            PendingIntent pending = PendingIntent.getForegroundService(this, 10, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            Notification.Action input = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_edit, "输入六位配对码", pending)
                    .addRemoteInput(new RemoteInput.Builder(KEY_CODE)
                            .setLabel("无线 ADB 配对码").build())
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

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) {}
        }
        discovery = null;
    }
    @Override public void onDestroy() {
        stopDiscovery();
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
