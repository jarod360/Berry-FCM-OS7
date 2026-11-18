package top.zesun.berryfcm;

import android.os.Bundle;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackgroundActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView policy, systemStates;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("后台保活", "监控和守护任务的运行设置");
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

        LinearLayout android = card("后台状态", "辅助权限不作为启动或继续监控的条件。");
        systemStates = text("", 13, false, muted);
        android.addView(systemStates);
        line(android, "授权管理", "自启动、关联启动、高耗电、锁定后台、通知和磁贴", () -> go(AuthActivity.class));
        LinearLayout androidExtra = disclosure(android, "高级 · Shell 策略与撤销", false);
        policy = text("", 12, false, muted);
        androidExtra.addView(policy);
        button(androidExtra, "放行 BerryFCM 后台策略", this::optimize, false);
        button(androidExtra, "撤销本版添加的后台策略", this::restoreOwn, false);
        button(androidExtra, "查看系统电池优化列表", () -> BackgroundSettings.batteryList(this), false);
        button(androidExtra, "单独放行 GMS 的 Doze", () -> gmsPolicy(true), false);
        button(androidExtra, "撤销 BerryFCM 添加的 GMS Doze 放行", () -> gmsPolicy(false), false);
        button(androidExtra, "放行 GMS 后台计量网络", () -> gmsNetwork(true), false);
        button(androidExtra, "撤销 BerryFCM 添加的后台网络放行", () -> gmsNetwork(false), false);

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
            if (privileged && value && !PrivilegeAccess.readyForUserAction(this)) {
                check.setChecked(false);
                error("请先完成授权并启动服务");
                go(AuthActivity.class);
                return;
            }
            change.apply(value);
            renderPolicy();
        });
    }
    private void syncService() {
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) FcmMonitorService.stop(this);
        else if (PrivilegeAccess.readyForUserAction(this)) FcmMonitorService.start(this);
    }
    private void renderPolicy() {
        if (systemStates == null) return;
        systemStates.setText("后台运行：" + (!PrivilegeAccess.readyForUserAction(this) ? "需要授权" : FcmMonitorService.serviceStatus(this))
                + "\nFCM 监控：" + (prefs.monitorEnabled() ? "已开启" : "未开启")
                + "\n应用守护：" + (prefs.guardEnabled() ? "已开启" : "未开启"));
    }

    private void optimize() {
        if (!PrivilegeAccess.readyForUserAction(this)) { go(AuthActivity.class); return; }
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
        if (!PrivilegeAccess.readyForUserAction(this)) { go(AuthActivity.class); return; }
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
        if (!PrivilegeAccess.readyForUserAction(this)) { go(AuthActivity.class); return; }
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
        if (!PrivilegeAccess.readyForUserAction(this)) { go(AuthActivity.class); return; }
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
        if (PrivilegeAccess.readyForUserAction(this)) FcmMonitorService.resumeIfNeeded(this);
        renderPolicy();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
