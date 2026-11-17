package top.zesun.berryfcm;

import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackgroundActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView policy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("后台保活与授权", "系统服务 · 电池策略 · OriginOS");
        LinearLayout service = card("自动运行", "后台状态独立于首页；系统强行停止仍可能中断，未采样时间会标记为未知。");
        toggle(service, "FCM 持续监控", prefs.monitorEnabled(), value -> {
            prefs.monitorEnabled(value);
            syncService();
        });
        toggle(service, "守护所选应用", prefs.guardEnabled(), value -> {
            prefs.guardEnabled(value);
            syncService();
        });
        toggle(service, "开机 / 更新后恢复已启用任务", prefs.startOnBoot(), prefs::startOnBoot);
        toggle(service, "监控通知低干扰模式", prefs.quietMonitor(), value -> {
            prefs.quietMonitor(value);
            syncService();
        });

        LinearLayout android = card("Android 后台权限", "优先使用系统授权接口；ADB Shell 优化完成后会回读当前配置。");
        policy = text("", 13, false, muted);
        android.addView(policy);
        button(android, "一键放行 BerryFCM 后台策略（ADB / Root）", this::optimize, true);
        button(android, "撤销本版 BerryFCM 添加的后台策略", this::restoreOwn, false);
        button(android, "打开忽略电池优化授权", () -> BackgroundSettings.requestBattery(this), false);
        button(android, "查看系统电池优化列表", () -> BackgroundSettings.batteryList(this), false);
        button(android, "打开应用设置（通知 / 电池）", () -> BackgroundSettings.details(this), false);
        button(android, "单独放行 GMS 的 Doze", () -> gmsPolicy(true), false);
        button(android, "撤销 BerryFCM 添加的 GMS Doze 放行", () -> gmsPolicy(false), false);
        button(android, "放行 GMS 后台计量网络", () -> gmsNetwork(true), false);
        button(android, "撤销 BerryFCM 添加的后台网络放行", () -> gmsNetwork(false), false);

        LinearLayout vendor = card("OriginOS 后台管理", "自启动、关联启动、允许后台高耗电通常为厂商私有开关，不能把打开设置页当成自动授权成功。");
        button(vendor, "打开 OriginOS 启动管理", () -> BackgroundSettings.autostart(this), true);
        line(vendor, "自启动 / 关联启动", "在系统启动管理内分别检查。尚无经证实可直达“关联启动”的独立入口，不能把同一页面重复伪装成两个入口。", null);
        button(vendor, "后台高耗电：进入应用信息", () -> BackgroundSettings.details(this), false);
        line(vendor, "最近任务锁定", "打开系统最近任务列表，找到 BerryFCM 卡片并从菜单锁定。此开关没有可验证的标准 Android 接口，需要手动操作。", null);
        toggle(vendor, "隐藏 BerryFCM 最近任务卡片", prefs.hideRecents(), value -> {
            if (RecentTasks.apply(this, value)) {
                prefs.hideRecents(value);
                error(value ? "任务卡片已请求隐藏；可在此关闭隐藏" : "已恢复任务卡片显示");
            } else error("系统未确认修改最近任务，保持原设置");
        });
        line(vendor, "注意", "隐藏任务卡片并不隐藏后台进程或 Android 前台服务提示。", null);
        renderPolicy();
    }

    private interface Change { void apply(boolean value); }
    private void toggle(LinearLayout box, String label, boolean checked, Change change) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setTextSize(14);
        check.setTextColor(ink);
        check.setChecked(checked);
        box.addView(check);
        check.setOnCheckedChangeListener((b, value) -> {
            boolean privileged = label.contains("FCM 持续监控")
                    || label.contains("守护所选应用");
            if (privileged && value && !prefs.shellRecentlyVerified()) {
                check.setChecked(false);
                error("请先进入授权中心验证 Root / ADB Shell");
                go(AuthActivity.class);
                return;
            }
            change.apply(value);
        });
    }
    private void syncService() {
        if (prefs.monitorEnabled() || prefs.guardEnabled()) FcmMonitorService.start(this);
        else FcmMonitorService.stop(this);
    }
    private void renderPolicy() {
        if (policy == null) return;
        android.app.NotificationManager notifications =
                getSystemService(android.app.NotificationManager.class);
        boolean notificationEnabled = notifications != null && notifications.areNotificationsEnabled();
        policy.setText("忽略系统电池优化："
                + (BackgroundSettings.batteryExempt(this) ? "已确认" : "未开启／未确认")
                + "\n通知权限：" + (notificationEnabled ? "已开启" : "未开启／未确认")
                + "\nRoot / ADB Shell：" + (prefs.shellRecentlyVerified()
                        ? "近期验证通过" : "当前未验证")
                + "\nOriginOS 自启动、关联启动与后台高耗电：需在系统页面手动确认");
    }
    private void optimize() {
        policy.setText("正在通过 Shell 设置并回读…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String result = BackgroundSettings.optimize(this, shell);
                runOnUiThread(() -> { policy.setText(result); error("已执行，核对上方回读结果"); });
            } catch (Exception error) {
                runOnUiThread(() -> policy.setText("自动设置失败：" + error.getMessage()));
            }
        });
    }
    private void restoreOwn() {
        policy.setText("正在恢复 BerryFCM 自己记录的后台策略…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String result = BackgroundSettings.restoreSelf(this, shell);
                runOnUiThread(() -> policy.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> policy.setText("撤销失败，保留回滚记录：" + error.getMessage()));
            }
        });
    }

    private void gmsPolicy(boolean allow) {
        policy.setText("正在修改 GMS 策略…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String result = BackgroundSettings.gmsDoze(this, shell, allow);
                runOnUiThread(() -> policy.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> policy.setText("操作未完成：" + error.getMessage()));
            }
        });
    }
    private void gmsNetwork(boolean allow) {
        policy.setText("正在检查 GMS UID 和后台计量网络规则…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String result = BackgroundSettings.gmsBackgroundNetwork(this, shell, allow);
                runOnUiThread(() -> policy.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> policy.setText("策略检查或修改失败：" + error.getMessage()));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        renderPolicy();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
