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
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
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
            // If mDNS is blocked by the ROM, permit "37123:123456" entirely
            // inside the notification, without switching back from Developer Options.
            java.util.regex.Matcher fallback = java.util.regex.Pattern.compile(
                    "^([0-9]{2,5})[ :#/]([0-9]{6})$").matcher(code);
            if (fallback.matches()) {
                selectedPort = Integer.parseInt(fallback.group(1));
                code = fallback.group(2);
            }
            if (!code.matches("[0-9]{6}") || selectedPort <= 0 || selectedPort > 65535) {
                publish("请输入六位码；若未发现端口，请输入“端口:六位码”", true, selectedPort);
                return START_NOT_STICKY;
            }
            stopDiscovery();
            publish("配对中，正在验证 ADB 身份…", false, 0);
            final int p = selectedPort;
            final String pairingCode = code;
            worker.execute(() -> pair(p, pairingCode));
            return START_NOT_STICKY;
        }
        new AppPrefs(this).shellInvalidate();
        port = intent.getIntExtra("manual_port", 0);
        publish(port > 0 ? "已指定配对端口 " + port + "；从通知输入配对码"
                : "正在发现本机配对端口；若自动发现失败，可在通知输入“端口:六位码”",
                true, port);
        if (port <= 0) startDiscovery();
        return START_NOT_STICKY;
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
                                // Accept only this phone's own ADB pairing service,
                                // not another Android device advertising mDNS on the LAN.
                                java.net.InetAddress address = resolved.getHost();
                                if (address == null || !localAddress(address)) return;
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

    private static boolean localAddress(java.net.InetAddress host) {
        try {
            if (host.isLoopbackAddress()) return true;
            java.util.Enumeration<java.net.NetworkInterface> devices =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (devices != null && devices.hasMoreElements()) {
                java.net.NetworkInterface device = devices.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = device.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (host.equals(addresses.nextElement())) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void pair(int selectedPort, String code) {
        try {
            AdbConnectionManager adb = AdbConnectionManager.get(this);
            if (!adb.pairLocal(selectedPort, code))
                throw new IllegalStateException("配对未成功，请核对配对码及端口");
            AppPrefs prefs = new AppPrefs(this);
            prefs.mode(AppPrefs.WIRELESS);
            prefs.markAdbPaired();
            PrivKitShell.allowStart(this);
            prefs.pairingStatus("配对成功，正在启动独立服务");
            // Pairing exchanges identity. Monitoring starts the independent
            // Priv Kit service once, then executes commands over Binder.
            prefs.monitorEnabled(true);
            boolean requested = FcmMonitorService.start(this);
            complete(requested
                    ? "配对成功，已请求启动监控；独立服务及采样状态请在应用内查看"
                    : "配对成功，系统未允许启动后台监控；返回应用后启动服务即可，无需重新配对");
        } catch (Exception error) {
            String message = String.valueOf(error.getMessage());
            new AppPrefs(this).shellFailed(message);
            if (message.contains("RSA routines") || message.contains("SSL library")) {
                complete("配对失败：TLS 握手异常，请检查系统无线调试并重试");
            } else complete("配对失败：" + message);
        }
    }

    private void complete(String message) {
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
                    .putExtra("port", selectedPort);
            PendingIntent pending = PendingIntent.getForegroundService(this, 10, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            Notification.Action input = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_edit,
                    selectedPort > 0 ? "输入六位配对码" : "输入配对端口和配对码", pending)
                    .addRemoteInput(new RemoteInput.Builder(KEY_CODE)
                            .setLabel(selectedPort > 0 ? "六位配对码" : "例如 37123:123456").build())
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
