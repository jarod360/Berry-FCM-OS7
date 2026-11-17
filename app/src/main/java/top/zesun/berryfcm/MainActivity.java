package top.zesun.berryfcm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

public final class MainActivity extends Ui {
    private TextView state;
    private TextView summary;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 10000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        title("BerryFCM Guardian", "FCM 连接、应用守护及后台运行");
        LinearLayout status = card("FCM 连接概览", "状态来自最近一次已保存的观测；无法采样时不推断在线。");
        state = text("尚无有效监测", 23, true, ink);
        status.addView(state);
        summary = text("", 13, false, muted);
        summary.setPadding(0, dp(10), 0, dp(5));
        status.addView(summary);
        button(status, "查看监控和断联时间线", () -> go(MonitorActivity.class), true);

        LinearLayout auth = card("授权状态", "单击进入独立授权模块。无线 ADB 配对码通过系统通知栏提交。");
        line(auth, "Root / ADB 授权", "当前模式：" + prefs.mode(), null);
        button(auth, "进入授权中心", () -> go(AuthActivity.class), true);

        LinearLayout menus = card("功能模块", "每项均进入独立页面。");
        line(menus, "守护应用", "已安装应用搜索、多选、停止状态检查和恢复", () -> go(GuardActivity.class));
        line(menus, "后台保活", "监控自动恢复、系统电池及 OriginOS 设置", () -> go(BackgroundActivity.class));
        line(menus, "网络诊断", "原生 FCM Diagnostics、网络检测与事件信息", () -> go(DiagnosticsActivity.class));
        line(menus, "设置与更新", "主题、通知、快捷磁贴、GitHub Release", () -> go(SettingsActivity.class));
    }

    private void render() {
        if (state == null) return;
        JSONObject data = FcmMonitorService.lastSnapshot(this);
        long sampled = data.optLong("fcm_sample_time", 0);
        long age = sampled == 0 ? Long.MAX_VALUE : System.currentTimeMillis() / 1000 - sampled;
        String error = FcmMonitorService.lastError(this);
        String observed = data.optString("fcm_state", "never_seen");
        if (sampled == 0) state.setText("未开始采样");
        else if (age > 90 || age < 0 || !error.isEmpty()) state.setText("状态未知 · 监控异常");
        else if ("connected".equals(observed)) state.setText("已观察到 FCM Socket");
        else if ("disconnected".equals(observed)) state.setText("未观察到 FCM Socket");
        else if ("connecting".equals(observed)) state.setText("连接尝试中");
        else state.setText("无法确认");
        summary.setText("后台监控：" + (prefs.monitorEnabled() ? "已启用" : "未启用")
                + "   守护：" + (prefs.guardEnabled() ? "已启用" : "未启用")
                + "\n上次采样：" + (sampled == 0 ? "无" : age + " 秒前")
                + "   已观测断联：" + data.optInt("fcm_outages", 0) + " 次"
                + (error.isEmpty() ? "" : "\n监控错误：" + error));
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
