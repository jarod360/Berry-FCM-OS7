package top.zesun.berryfcm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MonitorActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView current;
    private TextView events;
    private final Runnable update = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 10000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("FCM 连接监控", "连接证据、断联时间线与受控重连");
        LinearLayout control = card("持续监测", "目标每 15 秒检查一次。锁屏、网络、厂商调度可能导致采样间隔增长。");
        CheckBox enabled = new CheckBox(this);
        enabled.setText("启用 FCM 后台监控");
        enabled.setTextColor(ink);
        enabled.setChecked(prefs.monitorEnabled());
        control.addView(enabled);
        enabled.setOnCheckedChangeListener((b, value) -> {
            prefs.monitorEnabled(value);
            if (value) FcmMonitorService.start(this);
            else if (!prefs.guardEnabled()) FcmMonitorService.stop(this);
            render();
        });
        current = text("读取中…", 14, false, ink);
        current.setTextIsSelectable(true);
        current.setPadding(0, dp(12), 0, dp(9));
        control.addView(current);
        button(control, "立即执行一次检查", () -> {
            // The worker remains inside the same service; do not run an independent
            // shell concurrently or overwrite the single-writer state file.
            if (!prefs.monitorEnabled()) {
                error("请先开启后台监控");
                return;
            }
            FcmMonitorService.checkNow(this);
            error("已请求后台检查，稍后刷新状态");
        }, true);
        button(control, "手动请求 GMS FCM 重连", this::reconnect, false);

        LinearLayout timeline = card("最近事件", "这是采样时间，不是内核精确的断开时刻。短于采样间隔的断线可能漏检。");
        events = text("暂无记录", 13, false, ink);
        events.setTextIsSelectable(true);
        timeline.addView(events);
        button(timeline, "打开原生 Google FCM Diagnostics", () -> DiagnosticsActivity.openGoogle(this), false);
        button(timeline, "查看更多网络诊断", () -> go(DiagnosticsActivity.class), false);
    }

    private void render() {
        if (current == null) return;
        JSONObject j = FcmMonitorService.lastSnapshot(this);
        long sample = j.optLong("fcm_sample_time", 0);
        long age = sample == 0 ? -1 : System.currentTimeMillis() / 1000 - sample;
        String lastError = FcmMonitorService.lastError(this);
        String state = j.optString("fcm_state", "never_seen");
        if (age < 0 || age > 90 || !lastError.isEmpty()) state = "unknown（采样过期或出错）";
        current.setText("观测状态：" + state
                + "\n采样时间：" + time(sample) + (age < 0 ? "" : "（" + age + " 秒前）")
                + "\nSocket 证据：" + j.optString("fcm_transport", "无")
                + "  " + j.optString("fcm_remote", "")
                + "\n已观测连接时长：" + j.optLong("fcm_connected_seconds", 0) + " 秒"
                + "\n连续缺失观测：" + j.optLong("fcm_missing_seconds", 0) + " 秒"
                + "\n上次在线：" + time(j.optLong("fcm_last_seen", 0))
                + "\n上次断联：" + time(j.optLong("fcm_disconnect_since", 0))
                + "\n最近再次观察到连接：" + time(j.optLong("fcm_last_restore", 0))
                + "\n已观察到断联次数：" + j.optInt("fcm_outages", 0)
                + "\nSocket 更换次数：" + j.optInt("fcm_reconnects", 0)
                + "\n重连请求数：" + j.optInt("fcm_attempts", 0)
                + "\n最近请求结果：" + j.optString("fcm_last_result", "无")
                + (lastError.isEmpty() ? "" : "\n监控异常：" + lastError));
        String history = FcmMonitorService.lastEvents(this);
        String[] lines = history.split("\n");
        StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, lines.length - 24); i < lines.length; i++) {
            String[] parts = lines[i].split("\\|", 3);
            if (parts.length != 3) continue;
            String t;
            try { t = time(Long.parseLong(parts[0])); } catch (Exception ignored) { t = parts[0]; }
            result.append(t).append("  ").append(parts[1]).append("\n")
                  .append(parts[2]).append("\n\n");
        }
        events.setText(result.length() == 0 ? "暂无监控事件" : result.toString().trim());
    }

    private void reconnect() {
        if (!prefs.monitorEnabled()) { error("请先开启监控，并完成 Root / ADB 授权"); return; }
        worker.execute(() -> {
            try {
                String text = FcmMonitorService.manualReconnect(this);
                runOnUiThread(() -> error(text.isEmpty() ? "重连请求已发送，等待下一次连接观察" : text));
            } catch (Exception ex) {
                runOnUiThread(() -> error("重连请求失败：" + ex.getMessage()));
            }
        });
    }
    private String time(long seconds) {
        if (seconds <= 0) return "无";
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(seconds * 1000));
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
