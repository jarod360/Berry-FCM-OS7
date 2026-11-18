package top.zesun.berryfcm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Overview: FCM first. Permission guidance appears only when action is needed. */
public final class MainActivity extends Ui {
    private TextView state, summary, actionTitle, actionDetail;
    private Button monitorButton, actionButton;
    private LinearLayout issue;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 10000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        title("BerryFCM", "FCM 连接与后台守护");

        LinearLayout monitor = card("FCM 监控", "读取 Google Play 服务原生 FCM 状态；TCP 仅作辅助。");
        state = text("正在读取状态", 23, true, ink);
        monitor.addView(state);
        summary = text("", 13, false, muted);
        summary.setPadding(0, dp(8), 0, dp(6));
        monitor.addView(summary);
        monitorButton = button(monitor, "启动 FCM 监控", this::monitorAction, true);
        line(monitor, "监控详情", "断联记录与采样证据", () -> go(MonitorActivity.class));

        issue = card("需要处理", null);
        actionTitle = text("", 16, true, ink);
        issue.addView(actionTitle);
        actionDetail = text("", 13, false, muted);
        actionDetail.setMaxLines(4);
        actionDetail.setPadding(0, dp(5), 0, dp(5));
        issue.addView(actionDetail);
        actionButton = button(issue, "打开连接设置", () -> go(AuthActivity.class), false);

        LinearLayout quick = card("常用功能", null);
        line(quick, "应用守护", "所选应用检查与恢复", () -> go(GuardActivity.class));
        line(quick, "网络诊断", "无需授权的 FCM TCP 可达性检测", () -> go(DiagnosticsActivity.class));
        line(quick, "后台保活", "电池优化与系统启动管理", () -> go(BackgroundActivity.class));
        render();
    }

    private void monitorAction() {
        if (!prefs.monitorEnabled()) {
            if (AppPrefs.WIRELESS.equals(prefs.mode()) && !prefs.adbPaired()) {
                go(AuthActivity.class);
                return;
            }
            prefs.monitorEnabled(true);
            FcmMonitorService.start(this);
        } else {
            FcmMonitorService.checkNow(this);
        }
        render();
    }

    private void render() {
        if (state == null) return;
        boolean enabled = prefs.monitorEnabled();
        monitorButton.setText(enabled ? "立即采样" : "启动 FCM 监控");
        JSONObject snapshot = FcmMonitorService.lastSnapshot(this);
        long stamp = snapshot.optLong("fcm_sample_time", 0);
        long age = stamp == 0 ? -1 : System.currentTimeMillis() / 1000 - stamp;
        String lastError = FcmMonitorService.lastError(this);
        String observed = snapshot.optString("fcm_state", "never_seen");
        boolean nativeState = "gms".equals(snapshot.optString("fcm_source"));
        long attempt = FcmMonitorService.lastAttemptAt(this);
        String brief = lastError.replace('\n', ' ').replace('\r', ' ');
        if (brief.length() > 110) brief = brief.substring(0, 110) + "…";

        if (!enabled) state.setText("监控未开启");
        else if (!lastError.isEmpty()) state.setText("采样异常 · 等待自动恢复");
        else if (stamp <= 0) state.setText(attempt > 0 ? "等待首次有效采样" : "监控启动中");
        else if (age < 0 || age > 90) state.setText("采样过期 · 当前未知");
        else if (!nativeState) state.setText("FCM 原生状态无法确认");
        else if ("connected".equals(observed)) state.setText("FCM 已连接");
        else if ("connecting".equals(observed)) state.setText("FCM 正在重连");
        else if ("disconnected".equals(observed)) state.setText("FCM 已断联");
        else state.setText("FCM 状态尚不确定");

        summary.setText("监控 " + (enabled ? "已开启" : "未开启")
                + " · 应用守护 " + (prefs.guardEnabled() ? "已开启" : "未开启")
                + "\n最近有效采样：" + (stamp <= 0 ? "暂无" :
                        age < 0 ? "时间异常" : age + " 秒前")
                + "\n历史连接关闭事件：" + snapshot.optInt("fcm_native_close_events", 0) + " 条（非独立断联次数）"
                + (nativeState && age >= 0 && age <= 90 && lastError.isEmpty()
                    && snapshot.optLong("fcm_native_duration", -1) >= 0
                    ? "\n" + ("connected".equals(observed) ? "本次已连接：" : "本次未连接：")
                        + snapshot.optLong("fcm_native_duration") + " 秒" : "")
                + (attempt > 0 && stamp <= 0 ? "\n最近尝试："
                        + Math.max(0, (System.currentTimeMillis() - attempt) / 1000) + " 秒前" : "")
                + (brief.isEmpty() ? "" : "\n错误：" + brief));

        boolean initial = AppPrefs.WIRELESS.equals(prefs.mode()) && !prefs.adbPaired();
        boolean failed = enabled && !lastError.isEmpty();
        issue.setVisibility(initial || failed ? View.VISIBLE : View.GONE);
        if (initial) {
            actionTitle.setText("尚未完成无线 ADB 配对");
            actionDetail.setText("仅首次需要配对。配对后会自动连接并启动 FCM 采样。");
            actionButton.setText("前往无线配对");
        } else if (failed) {
            actionTitle.setText("监控正在自动重试");
            actionDetail.setText("配对身份仍保留，通常无需重新配对。"
                    + "先查看采样异常；只有身份失效时再进入连接设置。");
            actionButton.setText("检查连接与授权");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        RecentTasks.apply(this, prefs.hideRecents());
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }
    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
