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
    private Switch monitorSwitch, guardSwitch, bootSwitch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("后台保活", "监控和守护任务的运行设置");
        LinearLayout service = card("运行方式", "前台服务和系统设置只能提高可靠性，无法保证永不被停止。");
        monitorSwitch = toggle(service, "FCM 持续监控", prefs.monitorEnabled(), true, value -> {
            prefs.monitorEnabled(value);
            syncService();
        });
        guardSwitch = toggle(service, "守护所选应用", prefs.guardEnabled(), true, value -> {
            prefs.guardEnabled(value);
            syncService();
        });
        bootSwitch = toggle(service, "开机 / 更新后恢复已启用任务", prefs.startOnBoot(), false, prefs::startOnBoot);

        LinearLayout android = card("后台任务状态", "这些开关不能保证系统永远不会结束后台任务。");
        systemStates = text("", 13, false, muted);
        android.addView(systemStates);
        LinearLayout androidExtra = disclosure(android, "高级：系统省电与流量限制（一般无需修改）", false);
        TextView explanation = text("以下操作需要 Root、ADB 或 Shizuku 授权，修改的是 Android 系统限制，不是 FCM 连接本身。无法解决 Google 服务器不可达的问题。", 12, false, muted);
        androidExtra.addView(explanation);
        androidExtra.addView(text("BerryFCM 自身后台限制", 14, true, ink));
        androidExtra.addView(text("减少系统休眠和后台运行限制，可能提高监控存活率；不会保证永远运行。", 12, false, muted));
        button(androidExtra, "放宽 BerryFCM 后台限制", this::optimize, false);
        button(androidExtra, "恢复 BerryFCM 修改过的限制", this::restoreOwn, false);
        androidExtra.addView(text("Google 推送服务（GMS）", 14, true, ink));
        androidExtra.addView(text("休眠白名单：允许系统休眠时 Google 推送继续工作。如果上面已显示 GMS 在白名单中，就不必重复添加。", 12, false, muted));
        button(androidExtra, "添加 Google 推送到休眠白名单", () -> gmsPolicy(true), false);
        button(androidExtra, "撤销本应用添加的 Google 推送休眠放行", () -> gmsPolicy(false), false);
        androidExtra.addView(text("后台流量：仅针对 Android 的省流量模式限制；不能替代代理、VPN 或网络连接。", 12, false, muted));
        button(androidExtra, "允许 Google 推送使用后台计量流量", () -> gmsNetwork(true), false);
        button(androidExtra, "撤销本应用添加的后台流量放行", () -> gmsNetwork(false), false);
        androidExtra.addView(text("“恢复”只撤销 BerryFCM 有记录的改动，不会重置整个系统，也不会修改其他应用的权限。", 12, false, muted));
        policy = text("", 12, false, muted);
        policy.setTextIsSelectable(true);
        androidExtra.addView(policy);

        renderPolicy();
    }

    private interface Change { void apply(boolean value); }
    private Switch toggle(LinearLayout box, String label, boolean checked, boolean privileged, Change change) {
        Switch check = new Switch(this);
        check.setText(label);
        check.setTextSize(14);
        check.setMinHeight(dp(50));
        check.setTextColor(ink);
        check.setChecked(checked);
        box.addView(check);
        check.setOnCheckedChangeListener((b, value) -> {
            if (bindingSwitches()) return;
            if (privileged && value && !PrivilegeAccess.readyForUserAction(this)) {
                renderPolicy();
                error("请先完成授权并启动服务");
                go(AuthActivity.class);
                return;
            }
            change.apply(value);
            renderPolicy();
        });
        return check;
    }
    private void syncService() {
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) FcmMonitorService.stop(this);
        else if (PrivilegeAccess.readyForUserAction(this)) FcmMonitorService.start(this);
    }
    private void renderPolicy() {
        if (systemStates == null) return;
        syncChecked(monitorSwitch, prefs.monitorEnabled());
        syncChecked(guardSwitch, prefs.guardEnabled());
        syncChecked(bootSwitch, prefs.startOnBoot());
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
