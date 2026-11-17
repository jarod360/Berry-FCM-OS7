package top.zesun.berryfcm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Switch;
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
    private TextView current, technical, events, eventsFull;
    private final Runnable update = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 10000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("FCM 监控", "连接状态、采样记录与断联事件");
        LinearLayout control = card("当前状态", "约每 15 秒采样一次；过期或读取失败时显示未知。");
        Switch enabled = new Switch(this);
        enabled.setText("后台持续监控");
        enabled.setTextColor(ink);
        enabled.setChecked(prefs.monitorEnabled());
        control.addView(enabled);
        enabled.setOnCheckedChangeListener((b, value) -> {
            if (value && AppPrefs.WIRELESS.equals(prefs.mode()) && !prefs.adbPaired()) {
                enabled.setChecked(false);
                error("首次使用需要先完成无线 ADB 配对");
                go(AuthActivity.class);
                return;
            }
            prefs.monitorEnabled(value);
            if (value) FcmMonitorService.start(this);
            else if (!prefs.guardEnabled()) FcmMonitorService.stop(this);
            render();
        });
        current = text("读取中…", 14, false, ink);
        current.setTextIsSelectable(true);
        current.setPadding(0, dp(12), 0, dp(9));
        control.addView(current);
        button(control, "立即采样", () -> {
            // The worker remains inside the same service; do not run an independent
            // shell concurrently or overwrite the single-writer state file.
            if (!prefs.monitorEnabled()) {
                error("请先开启后台监控");
                return;
            }
            FcmMonitorService.checkNow(this);
            error("已请求后台检查，稍后刷新状态");
        }, true);
        button(control, "请求 FCM 重连", this::reconnect, false);
        LinearLayout extra = disclosure(control, "详细连接证据与统计", false);
        technical = text("暂无详细数据", 13, false, ink);
        technical.setTextIsSelectable(true);
        extra.addView(technical);

        LinearLayout timeline = card("最新事件", "显示最近 6 条。短于采样间隔的断连可能漏检。");
        events = text("暂无记录", 13, false, ink);
        events.setTextIsSelectable(true);
        timeline.addView(events);
        LinearLayout history = disclosure(timeline, "查看更多历史事件", false);
        eventsFull = text("暂无历史记录", 13, false, ink);
        eventsFull.setTextIsSelectable(true);
        history.addView(eventsFull);
        line(timeline, "网络诊断", "Google 原生诊断和 TCP 可达性", () -> go(DiagnosticsActivity.class));
    }

    private void render() {
        if (current == null) return;
        JSONObject j = FcmMonitorService.lastSnapshot(this);
        long sample = j.optLong("fcm_sample_time", 0);
        long age = sample == 0 ? -1 : System.currentTimeMillis() / 1000 - sample;
        String lastError = FcmMonitorService.lastError(this);
        String state = j.optString("fcm_state", "never_seen");
        long attempt = FcmMonitorService.lastAttemptAt(this);
        long serviceStart = FcmMonitorService.lastStartAt(this);
        if (!prefs.monitorEnabled()) state = "unknown（监控未开启）";
        else if (!lastError.isEmpty()) state = "unknown（最近一次采样失败）";
        else if (sample <= 0 && attempt > 0) state = "connecting";
        else if (sample <= 0 && serviceStart > 0) state = "connecting";
        else if (age < 0 || age > 90)
            state = "unknown（采样数据过期）";
        String readable = state.equals("connected") ? "观察到 FCM Socket"
                : state.equals("connecting") ? "正在建立连接"
                : state.equals("disconnected") ? "未观察到连接"
                : state.equals("stale") ? "采样数据已过期"
                : state.startsWith("unknown") ? "当前状态无法确认" : "暂无连接证据";
        String progress = FcmMonitorService.lastError(this).isEmpty()
                ? "后台将持续采样；如长时间无数据，请检查通知及系统后台策略。"
                : "后台正在自动重试，无需手动重复配对。";
        current.setText("FCM 状态：" + readable
                + "\n最近采样：" + (sample <= 0 ? "暂无" : time(sample)
                + (age < 0 ? "（时间异常）" : "（" + age + " 秒前）"))
                + "\n累计观察断联：" + j.optInt("fcm_outages", 0) + " 次"
                + (attempt > 0 ? "\n上次采样尝试：" + time(attempt / 1000) : "")
                + "\n" + progress
                + (lastError.isEmpty() ? "" : "\n采样异常：" + lastError));
        technical.setText("原始状态：" + state
                + "\nSocket：" + j.optString("fcm_transport", "无")
                + "  " + j.optString("fcm_remote", "")
                + "\n已观测连接时长：" + j.optLong("fcm_connected_seconds", 0) + " 秒"
                + "\n连续缺失观测：" + j.optLong("fcm_missing_seconds", 0) + " 秒"
                + "\n上次观察在线：" + time(j.optLong("fcm_last_seen", 0))
                + "\n上次观察断联：" + time(j.optLong("fcm_disconnect_since", 0))
                + "\n最近恢复观测：" + time(j.optLong("fcm_last_restore", 0))
                + "\nSocket 更换：" + j.optInt("fcm_reconnects", 0) + " 次"
                + "\n重连请求：" + j.optInt("fcm_attempts", 0) + " 次"
                + "\n最近请求结果：" + j.optString("fcm_last_result", "无")
                + "\n注意：采样并非内核精准时间，不代表推送必达。");
        String[] lines = FcmMonitorService.lastEvents(this).split("\n");
        events.setText(eventText(lines, 6));
        eventsFull.setText(eventText(lines, 24));
    }

    private String eventText(String[] lines, int maximum) {
        StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, lines.length - maximum); i < lines.length; i++) {
            String[] parts = lines[i].split("\\|", 3);
            if (parts.length != 3) continue;
            String stamp;
            try { stamp = time(Long.parseLong(parts[0])); }
            catch (NumberFormatException ignored) { stamp = parts[0]; }
            result.append(stamp).append("  ").append(parts[1]).append("\n")
                    .append(parts[2]).append("\n\n");
        }
        return result.length() == 0 ? "暂无监控事件" : result.toString().trim();
    }

    private void reconnect() {
        if (!prefs.monitorEnabled()) { error("请先开启 FCM 监控"); return; }
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
