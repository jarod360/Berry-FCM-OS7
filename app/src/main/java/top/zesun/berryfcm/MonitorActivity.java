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
    private TextView current, technical, events, eventsFull, recoveryEvents, environmentEvents;
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
        Switch automatic = new Switch(this);
        automatic.setText("断联后自动请求恢复");
        automatic.setTextColor(ink);
        automatic.setChecked(prefs.autoReconnect());
        control.addView(automatic);
        automatic.setOnCheckedChangeListener((button, enabledAuto) -> {
            prefs.autoReconnect(enabledAuto);
            if (prefs.monitorEnabled()) FcmMonitorService.checkNow(this);
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

        LinearLayout timeline = card("最新事件", "读取 GMS 原生事件；显示最近 6 条，历史缓冲区有容量限制。");
        events = text("暂无记录", 13, false, ink);
        events.setTextIsSelectable(true);
        timeline.addView(events);
        LinearLayout history = disclosure(timeline, "查看更多历史事件", false);
        eventsFull = text("暂无历史记录", 13, false, ink);
        eventsFull.setTextIsSelectable(true);
        history.addView(eventsFull);
        LinearLayout recovery = disclosure(timeline, "自动恢复与采样异常记录", false);
        recoveryEvents = text("暂无恢复记录", 13, false, ink);
        recoveryEvents.setTextIsSelectable(true);
        recovery.addView(recoveryEvents);
        LinearLayout environment = disclosure(timeline, "网络与休眠状态变化", false);
        environmentEvents = text("暂无环境记录", 13, false, ink);
        environmentEvents.setTextIsSelectable(true);
        environment.addView(environmentEvents);
        line(timeline, "网络诊断", "Google 原生诊断和 TCP 可达性", () -> go(DiagnosticsActivity.class));
    }

    private void render() {
        if (current == null) return;
        JSONObject j = FcmMonitorService.lastSnapshot(this);
        long sample = j.optLong("fcm_sample_time", 0);
        long age = FcmMonitorService.sampleAgeSeconds(this);
        String lastError = FcmMonitorService.lastError(this);
        String state = j.optString("fcm_state", "never_seen");
        boolean nativeState = "gms".equals(j.optString("fcm_source"));
        long attempt = FcmMonitorService.lastAttemptAt(this);
        long serviceStart = FcmMonitorService.lastStartAt(this);
        if (!prefs.monitorEnabled()) state = "unknown（监控未开启）";
        else if (!FcmMonitorService.isRunning()) state = "unknown（后台服务未运行）";
        else if (AppPrefs.WIRELESS.equals(prefs.mode()) && !PrivKitShell.isConnected())
            state = "unknown（独立服务未运行）";
        else if (!lastError.isEmpty()) state = "unknown（最近一次采样失败）";
        else if (sample <= 0 && (attempt > 0 || serviceStart > 0)) state = "waiting";
        else if (age < 0) state = "waiting";
        else if (age > 90) state = "unknown（采样数据过期）";
        else if (!nativeState) state = "unknown（原生状态不可读）";
        String readable = state.equals("connected") ? "FCM 已连接"
                : state.equals("connecting") ? "FCM 正在重连"
                : state.equals("disconnected") ? "FCM 已断联"
                : state.equals("waiting") ? "等待首次采样"
                : state.equals("stale") ? "采样数据已过期"
                : state.startsWith("unknown") ? "当前状态无法确认" : "暂无连接证据";
        String nativeReason = FcmMonitorService.describeNativeReason(j.optString("fcm_native_reason", "unrecognized"));
        String gate = j.optString("fcm_auto_gate", "unavailable");
        int retryIn = j.optInt("fcm_retry_in", 0);
        String progress = AppPrefs.WIRELESS.equals(prefs.mode()) && !PrivKitShell.isConnected()
                ? PrivKitShell.status() + "；可以在连接设置中启动服务，无需重新配对。"
                : FcmMonitorService.lastError(this).isEmpty()
                ? "后台将持续采样；如长时间无数据，请检查通知及系统后台策略。"
                : "后台正在自动重试，无需手动重复配对。";
        current.setText("FCM 状态：" + readable
                + "\n最近采样：" + (sample <= 0 ? "暂无" : time(sample)
                + (age < 0 ? "（上次运行的记录）" : "（" + age + " 秒前）"))
                + (prefs.monitorEnabled() && sample > 0 && age >= 0 && age <= 90
                    && !nativeState && lastError.isEmpty() ? "\n原因：" + nativeReason : "")
                + "\n保留的连接关闭事件：" + j.optInt("fcm_native_close_events", 0) + " 条（不是独立断联次数）"
                + (nativeState && !state.startsWith("unknown") && !state.equals("waiting")
                    && !state.equals("stale") && j.optLong("fcm_native_duration", -1) >= 0
                    ? "\n" + (state.equals("connected") ? "已连接：" : "未连接：")
                        + j.optLong("fcm_native_duration") + " 秒" : "")
                + (attempt > 0 ? "\n上次采样尝试：" + time(attempt / 1000) : "")
                + (nativeState && ("connecting".equals(state) || "disconnected".equals(state))
                    ? "\n恢复策略：" + (!prefs.autoReconnect() ? "仅监测" : "settling".equals(gate)
                        ? "等待网络稳定" : "offline".equals(gate) ? "当前无活动网络"
                        : "unavailable".equals(gate) ? "网络信息不可读取，自动恢复暂停"
                        : retryIn > 0 ? "请求冷却约 " + retryIn + " 秒" : "等待持续异常确认") : "")
                + "\n" + FcmMonitorService.serviceStatus(this)
                + (FcmMonitorService.nextAttemptIn(this) > 0 ? " · " + FcmMonitorService.nextAttemptIn(this) + " 秒后重试" : "")
                + "\n" + progress
                + (lastError.isEmpty() ? "" : "\n采样异常：" + lastError));
        JSONObject env = j.optJSONObject("app_environment");
        technical.setText("原始状态：" + state
                + "\n状态来源：" + (nativeState ? "Google GMS 原生 MCS" : nativeReason)
                + "\nTCP 辅助证据：" + j.optString("fcm_tcp_hint", "无")
                + "\n服务器：" + j.optString("fcm_transport", "无")
                + "  " + j.optString("fcm_remote", "")
                + (env == null ? "" : "\n采样环境（本应用）：" + env.optString("network", "unknown")
                    + " · 屏幕 " + localValue(env.optString("screen"))
                    + " · Doze " + localValue(env.optString("device_idle"))
                    + "\nGMS Doze 白名单：" + localValue(env.optString("gms_doze_exempt")))
                + "\n最后关闭错误码：" + j.optString("fcm_last_close_code", "无")
                + "\n原生连接成功事件：" + j.optInt("fcm_native_connected_events", 0) + " 条"
                + "\n上次观察在线：" + time(j.optLong("fcm_last_seen", 0))
                + "\n上次观察断联：" + time(j.optLong("fcm_disconnect_since", 0))
                + "\n最近恢复观测：" + time(j.optLong("fcm_last_restore", 0))
                + "\n重连请求：" + j.optInt("fcm_attempts", 0) + " 次"
                + "\n最近请求结果：" + recoveryLabel(j.optString("fcm_last_result", "none"))
                + "\n注意：GMS 原生历史有限，同一时刻的多网络关闭事件不等于多次整体断联。");
        String[] lines = FcmMonitorService.lastEvents(this).split("\n");
        events.setText(eventText(lines, 6));
        eventsFull.setText(eventText(lines, 24));
        recoveryEvents.setText(eventText(FcmMonitorService.lastRecoveryEvents(this).split("\\n"), 24));
        environmentEvents.setText(eventText(FcmMonitorService.lastEnvironmentEvents(this).split("\\n"), 24));
    }

    private static String localValue(String value) {
        switch (value) {
            case "yes": return "是";
            case "no": return "否";
            case "on": return "亮屏";
            case "off": return "熄屏";
            default: return "未知";
        }
    }
    private String recoveryLabel(String value) {
        switch (value) {
            case "requested": return "请求已提交，等待原生状态确认";
            case "failed": return "请求未确认";
            case "restored": return "请求后观察到原生恢复（不证明因果）";
            default: return "尚无请求";
        }
    }

    private String eventText(String[] lines, int maximum) {
        StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, lines.length - maximum); i < lines.length; i++) {
            String[] parts = lines[i].split("\\|", 3);
            if (parts.length != 3) continue;
            String stamp;
            try { stamp = time(Long.parseLong(parts[0])); }
            catch (NumberFormatException ignored) { stamp = parts[0]; }
            String label = parts[1].equals("gms_close") ? "连接关闭"
                    : parts[1].equals("gms_connected") ? "连接成功"
                    : parts[1].equals("gms_failed") ? "连接失败"
                    : parts[1].equals("gms_network_attempt") ? "网络连接尝试"
                    : parts[1].equals("gms_heartbeat_delay") ? "心跳调度延迟"
                    : parts[1].equals("reconnect_requested") ? "已请求恢复"
                    : parts[1].equals("reconnect_failed") ? "恢复请求失败"
                    : parts[1].equals("connection_restored") ? "原生状态恢复"
                    : parts[1].equals("reconnect_skipped") ? "恢复请求跳过"
                    : parts[1].equals("sample_gap") ? "采样中断"
                    : parts[1].equals("environment") ? "采样环境变化（非 GMS 路由证据）" : parts[1];
            result.append(stamp).append("  ").append(label).append("\n")
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
        FcmMonitorService.resumeIfNeeded(this);
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
