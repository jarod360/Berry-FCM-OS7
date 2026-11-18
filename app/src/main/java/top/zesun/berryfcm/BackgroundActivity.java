package top.zesun.berryfcm;

import android.os.Bundle;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackgroundActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView policy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("后台保活", "运行策略与系统权限");
        LinearLayout service = card("运行方式", "前台服务和系统设置只能提高可靠性，无法保证永不被停止。");
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

        LinearLayout android = card("系统后台权限", "优先完成系统电池授权，再按需使用 Root / ADB 修改策略。");
        policy = text("", 13, false, muted);
        android.addView(policy);
        button(android, "打开忽略电池优化授权", () -> BackgroundSettings.requestBattery(this), true);
        button(android, "打开应用设置（通知 / 电池）", () -> BackgroundSettings.details(this), false);
        LinearLayout androidExtra = disclosure(android, "高级 · Shell 策略与撤销", false);
        button(androidExtra, "放行 BerryFCM 后台策略", this::optimize, false);
        button(androidExtra, "撤销本版添加的后台策略", this::restoreOwn, false);
        button(androidExtra, "查看系统电池优化列表", () -> BackgroundSettings.batteryList(this), false);
        button(androidExtra, "单独放行 GMS 的 Doze", () -> gmsPolicy(true), false);
        button(androidExtra, "撤销 BerryFCM 添加的 GMS Doze 放行", () -> gmsPolicy(false), false);
        button(androidExtra, "放行 GMS 后台计量网络", () -> gmsNetwork(true), false);
        button(androidExtra, "撤销 BerryFCM 添加的后台网络放行", () -> gmsNetwork(false), false);

        LinearLayout vendor = card("OriginOS 设置", "厂商自启动、关联启动和高耗电开关需要在系统中手动确认。");
        button(vendor, "打开启动管理", () -> BackgroundSettings.autostart(this), true);
        button(vendor, "打开 BerryFCM 应用信息", () -> BackgroundSettings.details(this), false);
        LinearLayout vendorExtra = disclosure(vendor, "最近任务与手动设置说明", false);
        line(vendorExtra, "自启动 / 关联启动", "在系统启动管理中逐项检查，页面打开不代表已经授权。", null);
        line(vendorExtra, "最近任务锁定", "进入系统最近任务页面，通过 BerryFCM 任务卡片菜单手动锁定。", null);
        toggle(vendorExtra, "隐藏最近任务卡片", prefs.hideRecents(), value -> {
            if (RecentTasks.apply(this, value)) {
                prefs.hideRecents(value);
                error(value ? "已请求隐藏卡片；需要时可回到此处恢复" : "已请求显示任务卡片");
            } else error("系统未确认卡片操作，保持原设置");
        });
        line(vendorExtra, "说明", "隐藏最近任务卡片不隐藏后台进程，也不关闭前台服务通知。", null);
        renderPolicy();
    }

    private interface Change { void apply(boolean value); }
    private void toggle(LinearLayout box, String label, boolean checked, Change change) {
        Switch check = new Switch(this);
        check.setText(label);
        check.setTextSize(14);
        check.setMinHeight(dp(50));
        check.setTextColor(ink);
        check.setChecked(checked);
        box.addView(check);
        check.setOnCheckedChangeListener((b, value) -> {
            boolean privileged = label.contains("FCM 持续监控")
                    || label.contains("守护所选应用");
            if (privileged && value && AppPrefs.WIRELESS.equals(prefs.mode())
                    && !prefs.adbPaired()) {
                check.setChecked(false);
                error("首次使用需要先完成无线 ADB 配对");
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
        policy.setText("后台运行：" + FcmMonitorService.serviceStatus(this)
                + "\n忽略系统电池优化："
                + (BackgroundSettings.batteryExempt(this) ? "已确认" : "未开启／未确认")
                + "\n通知权限：" + (notificationEnabled ? "已开启" : "未开启／未确认")
                + "\n连接方式：" + (AppPrefs.WIRELESS.equals(prefs.mode())
                        ? prefs.adbPaired() ? PrivKitShell.status() : "尚未无线配对"
                        : AppPrefs.ROOT.equals(prefs.mode()) ? "Root（后台按需验证）"
                        : AppPrefs.SHIZUKU.equals(prefs.mode()) ? ShizukuShell.readiness()
                        : "静态 ADB TCP（后台按需连接）")
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
        FcmMonitorService.resumeIfNeeded(this);
        renderPolicy();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
