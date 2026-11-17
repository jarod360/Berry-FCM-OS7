package top.zesun.berryfcm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

public final class MainActivity extends Ui {
    private TextView state, summary, authorization, authDetail;
    private Button authButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 10000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        title("BerryFCM", "FCM 连接 · 应用守护 · 后台运行");

        // Always show authorization before privilege-dependent FCM observations.
        LinearLayout auth = card("运行前检查", "Root / ADB Shell 验证是特权采样的前提。");
        authorization = text("正在读取权限状态", 21, true, ink);
        auth.addView(authorization);
        authDetail = text("", 12, false, muted);
        authDetail.setPadding(0, dp(6), 0, dp(7));
        auth.addView(authDetail);
        authButton = button(auth, "进入授权中心", () -> go(AuthActivity.class), true);

        LinearLayout fcm = card("FCM 连接观测", "仅展示有证据的连接状态；无法读取或数据过期时不推断在线。");
        state = text("未开始采样", 21, true, ink);
        fcm.addView(state);
        summary = text("", 12, false, muted);
        summary.setPadding(0, dp(9), 0, dp(7));
        fcm.addView(summary);
        button(fcm, "查看监控与断联时间线", () -> go(MonitorActivity.class), false);

        LinearLayout navigation = card("功能中心", "点击对应模块进入独立页面。");
        line(navigation, "FCM 监控", "连接证据、历史事件与受控重连", () -> go(MonitorActivity.class));
        line(navigation, "应用守护", "已安装应用、stopped 检查和恢复", () -> go(GuardActivity.class));
        line(navigation, "后台保活", "系统电池策略与 OriginOS 设置", () -> go(BackgroundActivity.class));
        line(navigation, "网络诊断", "原生 Google FCM Diagnostics 与可达性", () -> go(DiagnosticsActivity.class));
        line(navigation, "设置与更新", "主题、快捷磁贴、GitHub Release", () -> go(SettingsActivity.class));
    }

    private void render() {
        if (state == null) return;
        String mode = prefs.mode();
        String label = AppPrefs.ROOT.equals(mode) ? "Root"
                : AppPrefs.WIRELESS.equals(mode) ? "无线 ADB"
                : AppPrefs.STATIC.equals(mode) ? "ADB TCP" : "USB 初始化";
        boolean valid = prefs.shellRecentlyVerified();
        String lastError = prefs.shellError();
        if (valid) {
            authorization.setText("Shell 已验证");
            authorization.setTextColor(ink);
            authDetail.setText(label + " · " + prefs.shellIdentity()
                    + "\n只证明 Shell 可用；GMS Socket 权限仍需实际采样确认。");
            authButton.setText("查看授权详情");
        } else {
            authorization.setText("需要授权或重新连接");
            authorization.setTextColor(accent);
            String reason = !mode.equals(prefs.shellMode()) ? "当前方式尚未验证 Shell"
                    : !lastError.isEmpty() ? "最近失败：" + lastError
                    : prefs.shellVerifiedAt() > 0 ? "最近验证已过期，需要重新确认"
                    : "尚未取得可用的 Root / ADB Shell";
            authDetail.setText(label + " · " + reason);
            authButton.setText("前往授权 / 验证连接");
        }

        JSONObject data = FcmMonitorService.lastSnapshot(this);
        long sampled = data.optLong("fcm_sample_time", 0);
        long age = sampled == 0 ? Long.MAX_VALUE : System.currentTimeMillis() / 1000 - sampled;
        String error = FcmMonitorService.lastError(this);
        String observed = data.optString("fcm_state", "never_seen");
        if (sampled == 0) state.setText("未开始采样");
        else if (!valid) state.setText("待授权 · 历史数据");
        else if (age > 90 || age < 0 || !error.isEmpty()) state.setText("未知 · 采样异常");
        else if ("connected".equals(observed)) state.setText("观察到 FCM Socket");
        else if ("disconnected".equals(observed)) state.setText("未观察到 FCM Socket");
        else if ("connecting".equals(observed)) state.setText("连接尝试中");
        else state.setText("无法确认");
        summary.setText("后台监控：" + (prefs.monitorEnabled() ? "已启用" : "未启用")
                + "   应用守护：" + (prefs.guardEnabled() ? "已启用" : "未启用")
                + "\n最近采样：" + (sampled == 0 ? "无" : age < 0 ? "时间异常" : age + " 秒前")
                + "   观察到断联：" + data.optInt("fcm_outages", 0) + " 次"
                + (error.isEmpty() ? "" : "\n最近监控错误：" + error));
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
