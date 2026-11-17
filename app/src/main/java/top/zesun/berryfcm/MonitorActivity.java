package top.zesun.berryfcm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** User controls for background sampling, ADB/Root optimizations, and event history. */
public final class MonitorActivity extends Activity {
    private AppPrefs prefs;
    private TextView current;
    private TextView history;
    private TextView permissions;
    private TextView optimizationReport;
    private CheckBox monitoring;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable update = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 10000L); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new AppPrefs(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        root.addView(label("BerryFCM · 后台监测", 23, true));
        root.addView(label("FCM Socket 观察、断联时间线和后台权限。退出本页面不停止已开启的监控。", 13, false));

        LinearLayout control = card(root, "后台监测");
        monitoring = new CheckBox(this);
        monitoring.setText("启用持续监测（前台服务，目标每 15 秒）");
        monitoring.setChecked(prefs.monitorEnabled());
        control.addView(monitoring);
        monitoring.setOnCheckedChangeListener((view, checked) -> {
            prefs.monitorEnabled(checked);
            if (checked) FcmMonitorService.start(this);
            else FcmMonitorService.stop(this);
            refresh();
        });

        CheckBox quiet = new CheckBox(this);
        quiet.setText("低干扰通知（静音、最低重要度）");
        quiet.setChecked(prefs.quietMonitor());
        control.addView(quiet);
        quiet.setOnCheckedChangeListener((button, checked) -> {
            prefs.quietMonitor(checked);
            if (prefs.monitorEnabled()) FcmMonitorService.start(this);
        });
        control.addView(label("通知可降低干扰，但不能隐藏 Android 系统的前台服务任务。厂商清理、强行停止、关机或无线调试关闭时不能保证持续运行。", 12, false));
        current = label("等待读取监测状态…", 13, false);
        current.setTextIsSelectable(true);
        control.addView(current);
        addButton(control, "刷新已保存的监测状态", this::refresh);

        LinearLayout optimization = card(root, "一键后台授权与优化");
        permissions = label("", 13, false);
        optimization.addView(permissions);
        optimizationReport = label("", 12, false);
        optimizationReport.setTextIsSelectable(true);
        optimization.addView(optimizationReport);
        addButton(optimization, "用 Root / 已配对 ADB 一键优化后台权限", () -> {
            final String mode = prefs.mode();
            if (!(AppPrefs.ROOT.equals(mode) || AppPrefs.WIRELESS.equals(mode)
                    || AppPrefs.STATIC.equals(mode))) {
                show("请先返回主页选择 Root、无线 ADB 或静态 ADB 授权模式");
                return;
            }
            show("正在通过已授权 Shell 配置后台策略…");
            worker.execute(() -> {
                try (PrivilegeShell shell = connectShell(mode)) {
                    String report = BackgroundSettings.optimize(shell);
                    runOnUiThread(() -> { optimizationReport.setText(report); refresh(); });
                } catch (Throwable error) {
                    runOnUiThread(() -> show("后台优化未完成：" + error.getMessage()));
                }
            });
        });
        optimization.addView(label("一键优化会尝试为 BerryFCM 放行 DeviceIdle 与后台 AppOps，并放行 GMS 的 Doze。仅以回读结果为准；Root / ADB 关闭后无法继续执行特权命令。", 12, false));

        LinearLayout settings = card(root, "OriginOS 后台设置入口");
        addButton(settings, "忽略电池优化（系统授权）", () -> BackgroundSettings.requestBatteryExemption(this));
        addButton(settings, "电池优化列表", () -> BackgroundSettings.openBatterySettings(this));
        addButton(settings, "OriginOS 自启动 / 关联启动", () -> BackgroundSettings.openAutostart(this));
        addButton(settings, "应用详情（电池、通知、后台耗电）", () -> BackgroundSettings.openAppDetails(this));
        settings.addView(label("OriginOS 的自启动、关联启动、允许后台高耗电和最近任务锁定属于厂商管理。若无法经公开系统接口直接配置，需要在跳转页面手动确认。最近任务锁定通常在多任务卡片菜单中设置。", 12, false));

        LinearLayout events = card(root, "最近连接事件");
        history = label("尚无事件", 12, false);
        history.setTextIsSelectable(true);
        events.addView(history);
        addButton(events, "读取事件历史", this::refresh);
        addButton(events, "打开 Google 原生 FCM Diagnostics", this::openDiagnostics);

        root.addView(label("状态依据：Root/ADB Shell 读取 GMS UID Socket。只有 WRITE_SECURE_SETTINGS 权限不能读取其它应用的连接。短于采样间隔的断联可能漏检；采样中断将标记为未知。", 12, false));
        setContentView(scroll);
        refresh();
    }

    private PrivilegeShell connectShell(String mode) throws Exception {
        if (AppPrefs.ROOT.equals(mode)) {
            PrivilegeShell root = new RootShell();
            try {
                if (!root.execute("id").contains("uid=0")) throw new IllegalStateException("Root 未授权");
                return root;
            } catch (Exception error) {
                root.close();
                throw error;
            }
        }
        AdbConnectionManager adb = AdbConnectionManager.get(this);
        boolean ready = AppPrefs.WIRELESS.equals(mode)
                ? adb.autoConnectLocal(this)
                : adb.connectStatic(prefs.host(), prefs.staticPort());
        if (!ready) throw new IllegalStateException("ADB 未连接；先在主页配对或开启调试端口");
        return new AdbShell(this);
    }

    private void refresh() {
        if (current == null || history == null || permissions == null) return;
        long now = System.currentTimeMillis() / 1000;
        JSONObject state = FcmMonitorService.lastSnapshot(this);
        long sample = state.optLong("fcm_sample_time", 0);
        long age = sample == 0 ? -1 : now - sample;
        String value = state.optString("fcm_state", "never_seen");
        String[] lines = {
                "授权模式：" + prefs.mode(),
                "后台监测开关：" + (prefs.monitorEnabled() ? "已启用" : "已关闭"),
                "当前状态：" + (age < 0 ? "未开始采样" : age > 90 ? "监控数据过期（未知）" : value),
                "Socket：" + state.optString("fcm_transport", "") + "  " + state.optString("fcm_remote", ""),
                "最近采样：" + (sample == 0 ? "无" : formatTime(sample) + "（" + age + " 秒前）"),
                "最近在线：" + formatTime(state.optLong("fcm_last_seen", 0)),
                "最近断联：" + formatTime(state.optLong("fcm_disconnect_since", 0)),
                "最近恢复：" + formatTime(state.optLong("fcm_last_restore", 0)),
                "当前连接观测时长：" + state.optLong("fcm_connected_seconds", 0) + " 秒",
                "Socket 更换次数：" + state.optInt("fcm_reconnects", 0),
                "累计观测到断联：" + state.optInt("fcm_outages", 0),
                "重连请求次数：" + state.optInt("fcm_attempts", 0),
                "最近请求结果：" + state.optString("fcm_last_result", "无"),
                "最新监控错误：" + FcmMonitorService.lastError(this)
        };
        current.setText(String.join("\n", lines));
        String eventText = FcmMonitorService.lastEvents(this);
        String[] all = eventText.split("\n");
        StringBuilder recent = new StringBuilder();
        for (int i = Math.max(0, all.length - 24); i < all.length; i++) {
            String entry = all[i];
            if (entry.isEmpty()) continue;
            String[] parts = entry.split("\\|", 3);
            if (parts.length == 3) {
                try {
                    entry = formatTime(Long.parseLong(parts[0])) + "  " + parts[1] + "\n" + parts[2];
                } catch (NumberFormatException ignored) { }
            }
            recent.append(entry).append("\n\n");
        }
        history.setText(recent.length() == 0 ? "尚无事件" : recent.toString().trim());
        permissions.setText("本应用已获电池优化豁免：" +
                (BackgroundSettings.ignoringBatteryOptimizations(this) ? "是" : "否") +
                "\n可使用上方按钮尝试一键授权（Root 或已连接 ADB）。");
    }

    private static String formatTime(long epoch) {
        if (epoch <= 0) return "无";
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(epoch * 1000));
    }

    private void openDiagnostics() {
        try {
            Intent target = new Intent();
            target.setClassName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics");
            startActivity(target);
        } catch (RuntimeException error) {
            show("无法直接打开 GMS Diagnostics；当前 GMS 版本可能未导出该入口");
        }
    }

    private LinearLayout card(LinearLayout root, String heading) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        root.addView(box, params);
        box.addView(label(heading, 17, true));
        return box;
    }
    private TextView label(String value, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.rgb(35, 51, 75));
        v.setTextSize(size);
        v.setPadding(0, dp(4), 0, dp(5));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private void addButton(LinearLayout parent, String caption, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(caption);
        parent.addView(button);
        button.setOnClickListener(v -> action.run());
    }
    private void show(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(update);
        ui.post(update);
    }
    @Override protected void onPause() {
        ui.removeCallbacks(update);
        super.onPause();
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
