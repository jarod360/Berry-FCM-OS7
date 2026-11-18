package top.zesun.berryfcm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Switch;
import android.widget.Button;
import android.view.View;
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
    private TextView headline, current, technical, events, eventsFull, recoveryEvents, environmentEvents, manualResult;
    private Button checkButton, reconnectButton;
    private Switch enabled, automatic;
    private final Runnable update = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 10000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("FCM 监控", "连接状态、检查记录与断开事件");
        LinearLayout control = card("当前状态", "在后台检查 FCM 连接是否正常。");
        headline = text("等待检查", 22, true, muted);
        control.addView(headline);
        current = text("读取中…", 14, false, muted);
        current.setTextIsSelectable(true);
        current.setPadding(0, dp(8), 0, dp(10));
        control.addView(current);
        enabled = new Switch(this);
        enabled.setText("后台持续监控");
        enabled.setTextColor(ink);
        enabled.setMinHeight(dp(48));
        enabled.setChecked(prefs.monitorEnabled());
        control.addView(enabled);
        enabled.setOnCheckedChangeListener((b, value) -> {
            if (bindingSwitches()) return;
            if (value && !authorizationReady()) {
                syncChecked(enabled, prefs.monitorEnabled());
                error("请先完成授权");
                go(AuthActivity.class);
                return;
            }
            prefs.monitorEnabled(value);
            if (value) FcmMonitorService.start(this);
            else if (!prefs.guardEnabled()) FcmMonitorService.stop(this);
            render();
        });
        automatic = new Switch(this);
        automatic.setText("断开后自动请求恢复");
        automatic.setTextColor(ink);
        automatic.setMinHeight(dp(48));
        automatic.setChecked(prefs.autoReconnect());
        control.addView(automatic);
        automatic.setOnCheckedChangeListener((button, enabledAuto) -> {
            if (bindingSwitches()) return;
            prefs.autoReconnect(enabledAuto);
            if (prefs.monitorEnabled() && authorizationReady() && FcmMonitorService.isRunning()) FcmMonitorService.checkNow(this);
            render();
        });
        checkButton = button(control, "立即检查", () -> {
            if (!authorizationReady()) { go(AuthActivity.class); return; }
            // The worker remains inside the same service; do not run an independent
            // shell concurrently or overwrite the single-writer state file.
            if (!prefs.monitorEnabled()) prefs.monitorEnabled(true);
            syncChecked(enabled, prefs.monitorEnabled());
            FcmMonitorService.checkNow(this);
            render();
        }, true);
        reconnectButton = button(control, "尝试重新连接", this::reconnect, false);
        manualResult = text("", 13, false, muted);
        manualResult.setTextIsSelectable(true);
        control.addView(manualResult);
        manualResult.setVisibility(View.GONE);
        LinearLayout extra = disclosure(control, "详细连接证据与统计", false);
        technical = text("暂无详细数据", 13, false, ink);
        technical.setTextIsSelectable(true);
        extra.addView(technical);

        LinearLayout timeline = card("最近事件", "显示最近六条记录。");
        events = text("暂无记录", 13, false, ink);
        events.setTextIsSelectable(true);
        timeline.addView(events);
        LinearLayout history = disclosure(timeline, "历史记录", false);
        eventsFull = text("暂无历史记录", 13, false, ink);
        eventsFull.setTextIsSelectable(true);
        history.addView(eventsFull);
        LinearLayout recovery = disclosure(timeline, "恢复记录", false);
        recoveryEvents = text("暂无恢复记录", 13, false, ink);
        recoveryEvents.setTextIsSelectable(true);
        recovery.addView(recoveryEvents);
        LinearLayout environment = disclosure(timeline, "网络与休眠", false);
        environmentEvents = text("暂无环境记录", 13, false, ink);
        environmentEvents.setTextIsSelectable(true);
        environment.addView(environmentEvents);
        line(timeline, "网络诊断", "Google 原生诊断和 TCP 可达性", () -> go(DiagnosticsActivity.class));
    }

    private boolean authorizationReady() {
        return PrivilegeAccess.readyForUserAction(this);
    }

    private void render() {
        if (current == null) return;
        boolean ready = authorizationReady();
        syncChecked(enabled, prefs.monitorEnabled());
        syncChecked(automatic, prefs.autoReconnect());
        checkButton.setText(!ready ? "去授权" : !prefs.monitorEnabled() ? "开启监控" : !FcmMonitorService.isRunning() ? "启动监控" : "立即检查");
        String manual = FcmMonitorService.manualReconnectResult(this);
        manualResult.setVisibility(manual.isEmpty() ? View.GONE : View.VISIBLE);
        manualResult.setText("手动重连：" + manual);
        reconnectButton.setEnabled(!manual.startsWith("正在读取"));
        JSONObject j = FcmMonitorService.lastSnapshot(this);
        long sample = j.optLong("fcm_sample_time", 0);
        long age = FcmMonitorService.sampleAgeSeconds(this);
        String lastError = FcmMonitorService.currentError(this);
        String state = j.optString("fcm_state", "never_seen");
        boolean nativeState = "gms".equals(j.optString("fcm_source"));
        long attempt = FcmMonitorService.lastAttemptAt(this);
        long serviceStart = FcmMonitorService.lastStartAt(this);
        if (!prefs.monitorEnabled()) state = "unknown（监控未开启）";
        else if (!FcmMonitorService.isRunning()) state = "unknown（后台服务未运行）";
        else if (AppPrefs.WIRELESS.equals(prefs.mode()) && !PrivKitShell.isConnected())
            state = "unknown（独立服务未运行）";
        else if (!lastError.isEmpty()) state = "unknown（最近一次检查失败）";
        else if (sample <= 0 && (attempt > 0 || serviceStart > 0)) state = "waiting";
        else if (age < 0) state = "waiting";
        else if (age > 90) state = "unknown（检查数据过期）";
        else if (!nativeState) state = "unknown（原生状态不可读）";
        MonitorStatus.State verdict = MonitorStatus.resolve(ready, prefs.monitorEnabled(),
                FcmMonitorService.isRunning(), sample > 0 ? age : -1,
                !lastError.isEmpty(), nativeState, j.optString("fcm_state"));
        headline.setText(verdict.label);
        headline.setTextColor(statusColor(verdict));
        String nativeReason = FcmMonitorService.describeNativeReason(j.optString("fcm_native_reason", "unrecognized"));
        String gate = j.optString("fcm_auto_gate", "unavailable");
        int retryIn = j.optInt("fcm_retry_in", 0);
        String progress = AppPrefs.WIRELESS.equals(prefs.mode()) && !PrivKitShell.isConnected()
                ? PrivKitShell.status() + "；可以在连接设置中启动服务，无需重新配对。"
                : FcmMonitorService.currentError(this).isEmpty()
                ? "后台将持续检查；如长时间无数据，请检查通知及系统后台策略。"
                : "后台正在自动重试，无需手动重复配对。";
        // Keep the main page actionable; place event counts and retry details below.
        boolean fresh = ready && prefs.monitorEnabled() && FcmMonitorService.isRunning()
                && age >= 0 && age <= 90 && nativeState && lastError.isEmpty();
        boolean disconnected = "connecting".equals(state) || "disconnected".equals(state);
        reconnectButton.setVisibility(fresh && disconnected ? View.VISIBLE : View.GONE);
        StringBuilder overview = new StringBuilder("最近检查：")
                .append(sample <= 0 ? "暂无" : age < 0 ? "上次运行"
                        : age + " 秒前");
        if (fresh && j.optLong("fcm_native_duration", -1) >= 0) {
            overview.append("\\n")
                    .append("connected".equals(state) ? "已连接：" : "未连接：")
                    .append(MonitorStatus.duration(j.optLong("fcm_native_duration")));
        }
        if (fresh && disconnected) {
            overview.append("\\n自动恢复：")
                    .append(prefs.autoReconnect()
                            ? "ready".equals(gate) ? recoveryDecision(j, retryIn)
                                    : "offline".equals(gate) ? "等待网络"
                                    : "等待网络稳定"
                            : "已关闭");
        }
        if (!lastError.isEmpty()) overview.append("\\n检查失败，后台重试中");
        current.setText(overview.toString());
        if (!ready) current.setText("请先完成授权。以下为历史记录。");
        else if (!prefs.monitorEnabled()) current.setText("监控未开启");
        else if (!FcmMonitorService.isRunning()) current.setText("监控未运行，点击启动。");
        JSONObject env = j.optJSONObject("app_environment");
        technical.setText((age < 0 || age > 90 || !ready || !prefs.monitorEnabled()
                || !lastError.isEmpty() ? "以下是历史证据，不代表当前连接。\n" : "")
                + "原始状态：" + state
                + "\n状态来源：" + (nativeState ? "Google GMS 原生 MCS" : nativeReason)
                + "\nTCP 辅助证据：" + j.optString("fcm_tcp_hint", "无")
                + "\n服务器：" + j.optString("fcm_transport", "无")
                + "  " + j.optString("fcm_remote", "")
                + (env == null ? "" : "\n检查环境（本应用）：" + env.optString("network", "unknown")
                    + " · 屏幕 " + localValue(env.optString("screen"))
                    + " · Doze " + localValue(env.optString("device_idle"))
                    + "\nGMS Doze 白名单：" + localValue(env.optString("gms_doze_exempt")))
                + "\n原生关闭事件：" + j.optInt("fcm_native_close_events", 0) + " 条"
                + "\n上次检查尝试：" + time(attempt / 1000)
                + "\n后台状态：" + FcmMonitorService.serviceStatus(this)
                + (FcmMonitorService.nextAttemptIn(this) > 0 ? "（" + FcmMonitorService.nextAttemptIn(this) + " 秒后重试）" : "")
                + "\n最后关闭错误码：" + j.optString("fcm_last_close_code", "无")
                + "\n原生连接成功事件：" + j.optInt("fcm_native_connected_events", 0) + " 条"
                + "\n上次观察在线：" + time(j.optLong("fcm_last_seen", 0))
                + "\n上次观察断开：" + time(j.optLong("fcm_disconnect_since", 0))
                + "\n最近恢复观测：" + time(j.optLong("fcm_last_restore", 0))
                + "\n重连请求：" + j.optInt("fcm_attempts", 0) + " 次"
                + "\n本次连续恢复请求：" + j.optInt("fcm_retry_streak", 0) + " 次"
                + "\n最近请求结果：" + recoveryLabel(j.optString("fcm_last_result", "none"))
                + "\n注意：GMS 原生历史有限，同一时刻的多网络关闭事件不等于多次整体断开。");
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
    private String recoveryDecision(JSONObject j, int retryIn) {
        switch (j.optString("fcm_recovery_decision", "")) {
            case "gms_stopped_unknown": return "GMS 停止状态未通过检查，未发送请求";
            case "requested": return "命令已返回，等待原生连接确认";
            case "request_failed": return "重连请求失败，见恢复记录";
            case "confirmation_changed": return "第二次检查状态变化，暂不干预";
            case "cooldown": return "等待下次恢复 · 约 " + retryIn + " 秒";
            case "waiting_confirmation": return "等待第二次原生检查";
            case "disabled": return "自动恢复已关闭";
            case "network_gate": return "等待网络恢复或切换稳定";
            case "waiting_duration":
                long seconds = j.optLong("fcm_observed_down_seconds", -1);
                return "尚未达到恢复条件" + (seconds < 0 ? "" : "（本轮已观察 " + seconds + " 秒）");
            default: return "等待有效恢复决策";
        }
    }
    private String recoveryLabel(String value) {
        switch (value) {
            case "requested": return "命令已返回，未确认投递或恢复";
            case "failed": return "请求未确认";
            case "restored": return "请求后观察到原生恢复（不证明因果）";
            default: return "尚无请求";
        }
    }

    private String eventText(String[] lines, int maximum) {
        StringBuilder result = new StringBuilder();
        for (int i = lines.length - 1; i >= Math.max(0, lines.length - maximum); i--) {
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
                    : parts[1].equals("reconnect_requested") ? "恢复命令已返回"
                    : parts[1].equals("reconnect_failed") ? "恢复请求失败"
                    : parts[1].equals("recovery_confirmed") ? "休眠后确认仍未连接"
                    : parts[1].equals("connection_down") ? "确认 FCM 已断开"
                    : parts[1].equals("connection_restored") ? "原生状态恢复"
                    : parts[1].equals("reconnect_skipped") ? "恢复请求跳过"
                    : parts[1].equals("sample_gap") ? "检查中断"
                    : parts[1].equals("environment") ? "检查环境变化（非 GMS 路由证据）" : parts[1];
            result.append(stamp).append("  ").append(label).append("\n")
                    .append(parts[2]).append("\n\n");
        }
        return result.length() == 0 ? "暂无监控事件" : result.toString().trim();
    }

    private void reconnect() {
        if (!authorizationReady()) { go(AuthActivity.class); return; }
        if (!prefs.monitorEnabled() || !FcmMonitorService.isRunning()) { error("请先开启 FCM 监控"); return; }
        worker.execute(() -> {
            try {
                String text = FcmMonitorService.manualReconnect(this);
                runOnUiThread(() -> { if (!isDestroyed()) { error(text); render(); } });
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
        if (authorizationReady()) FcmMonitorService.resumeIfNeeded(this);
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
