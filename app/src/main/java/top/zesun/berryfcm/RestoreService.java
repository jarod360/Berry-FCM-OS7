package top.zesun.berryfcm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RestoreService extends Service {
    private static final String CHANNEL = "berry_fcm_restore";
    private static final int NOTIFICATION_ID = 7210;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static void start(Context context, String reason) {
        Intent intent = new Intent(context, RestoreService.class).putExtra("reason", reason);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException ignored) {
            // Android/OEM background-start restrictions are reported in the app's setup guidance.
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("正在连接授权通道…"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        worker.execute(() -> runRestore(startId));
        return START_NOT_STICKY;
    }

    private void runRestore(int startId) {
        PrivilegeShell shell = null;
        String result;
        try {
            AppPrefs prefs = new AppPrefs(this);
            Set<String> selected = prefs.selected();
            String mode = prefs.mode();
            if (selected.isEmpty() && prefs.managed().isEmpty()) {
                result = "没有配置需要管理的无障碍服务";
            } else {
                if (AppPrefs.ROOT.equals(mode)) {
                    shell = new RootShell();
                    String id = shell.execute("id");
                    if (!id.contains("uid=0")) throw new IllegalStateException("Root 未授权：" + id.trim());
                } else if (AppPrefs.WIRELESS.equals(mode)) {
                    waitForNetwork();
                    AdbConnectionManager adb = AdbConnectionManager.get(this);
                    if (!adb.autoConnectLocal(this)) throw new IllegalStateException("无线调试未连接；请确认无线调试已开启且设备已配对");
                    shell = new AdbShell(this);
                } else if (AppPrefs.STATIC.equals(mode)) {
                    waitForNetwork();
                    AdbConnectionManager adb = AdbConnectionManager.get(this);
                    if (!adb.connectStatic(prefs.host(), prefs.staticPort())) throw new IllegalStateException("静态端口连接失败");
                    shell = new AdbShell(this);
                } else {
                    throw new IllegalStateException("当前模式需要手动操作或电脑 USB ADB，不能在开机时自动恢复");
                }
                Set<String> previous = prefs.managed();
                String command = AccessibilityOps.applyCommand(previous, selected);
                String output = shell.execute(command);
                String verify = shell.execute(AccessibilityOps.readEnabledCommand());
                int enabled = 0;
                for (String component : selected) if (verify.contains(component)) enabled++;
                for (String component : previous) if (!selected.contains(component) && verify.contains(component))
                    throw new IllegalStateException("系统仍返回已取消的服务：" + component);
                if (enabled != selected.size()) throw new IllegalStateException("系统未确认全部目标服务，实际 " + enabled + "/" + selected.size());
                prefs.managed(selected);
                result = "已提交恢复并复查：" + enabled + "/" + selected.size() + " 个目标服务可见\n" + output.trim();
            }
        } catch (Throwable e) {
            result = "恢复未完成：" + e.getMessage();
        } finally {
            if (shell != null) shell.close();
        }
        updateNotification(result);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf(startId);
    }


    private void waitForNetwork() throws InterruptedException {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        for (int i = 0; i < 30; i++) {
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null || cm == null ? null : cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return;
            Thread.sleep(2000);
        }
        throw new IllegalStateException("开机后 60 秒内没有可用网络");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "BerryFCM 恢复", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("执行用户明确选择的无障碍服务恢复任务");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("BerryFCM Guardian")
                .setContentText(text.length() > 120 ? text.substring(0, 117) + "…" : text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text));
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
