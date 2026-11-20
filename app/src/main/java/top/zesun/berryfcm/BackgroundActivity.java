package top.zesun.berryfcm;

import android.os.Bundle;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.view.View;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackgroundActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView policy, systemStates;
    private Switch monitorSwitch, guardSwitch, bootSwitch;
    private RadioGroup wakeChoices;
    private int smartChoiceId, continuousChoiceId;
    private boolean syncingWakeChoices;
    private Button intervalButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("后台保活", "监控和守护任务的运行设置");
        LinearLayout service = card("运行方式", "前台服务和系统设置只能提高可靠性，无法保证永不被停止。");
        monitorSwitch = toggle(service, "FCM 监控", prefs.monitorEnabled(), true, value -> {
            prefs.monitorEnabled(value);
            syncService();
        });
        guardSwitch = toggle(service, "守护所选应用", prefs.guardEnabled(), true, value -> {
            prefs.guardEnabled(value);
            syncService();
        });
        bootSwitch = toggle(service, "开机 / 更新后恢复已启用任务", prefs.startOnBoot(), false, prefs::startOnBoot);

        LinearLayout power = card("熄屏巡检方式", "两种方式均保留原生 FCM 状态判断与受控恢复；系统 Doze 下闹钟可能延迟。");
        wakeChoices = new RadioGroup(this);
        wakeChoices.setOrientation(RadioGroup.VERTICAL);
        RadioButton smart = new RadioButton(this);
        smart.setId(View.generateViewId());
        smartChoiceId = smart.getId();
        smart.setText("智能省电 · 任务执行时短时持锁");
        smart.setTextSize(14);
        smart.setTextColor(ink);
        smart.setMinHeight(dp(52));
        RadioButton continuous = new RadioButton(this);
        continuous.setId(View.generateViewId());
        continuousChoiceId = continuous.getId();
        continuous.setText("持续唤醒 · 更及时的熄屏巡检（耗电较高）");
        continuous.setTextSize(14);
        continuous.setTextColor(ink);
        continuous.setMinHeight(dp(52));
        wakeChoices.addView(smart);
        wakeChoices.addView(continuous);
        power.addView(wakeChoices);
        syncWakeChoice();
        wakeChoices.setOnCheckedChangeListener((group, id) -> {
            if (syncingWakeChoices) return;
            String selected = id == continuousChoiceId ? AppPrefs.WAKE_CONTINUOUS
                    : id == smartChoiceId ? AppPrefs.WAKE_SMART : null;
            if (selected == null || selected.equals(prefs.wakeMode())) return;
            if (AppPrefs.WAKE_CONTINUOUS.equals(selected)) {
                confirmAction("开启持续唤醒？",
                        "持续唤醒会尽量保持 CPU 运行，以提高熄屏巡检及时性。"
                        + "\n可能增加待机耗电、发热，并阻止真正的深度休眠。"
                        + "\n系统仍可能停止应用，无法保证每分钟必定检查。",
                        "仍然开启", () -> applyWakeMode(selected), this::syncWakeChoice);
            } else applyWakeMode(selected);
        });
        power.addView(text("持续唤醒仅在 FCM 监控开启时生效，守护单独运行不会持续持锁。"
                + "\n智能省电依靠实际调度事件触发检查；无事件且 CPU 挂起时可能延迟数分钟。"
                + "\n从 alpha.89 升级会保留原有持续唤醒，便于对照测试。", 12, false, muted));
        intervalButton = button(power, intervalLabel(), this::chooseInterval, false);

        LinearLayout android = card("后台任务状态", "这些开关不能保证系统永远不会结束后台任务。");
        systemStates = text("", 13, false, muted);
        android.addView(systemStates);
        LinearLayout androidExtra = disclosure(android, "高级：系统省电与流量限制（一般无需修改）", false);
        TextView explanation = text("以下操作需要 Root、ADB 或 Shizuku 授权，修改的是 Android 系统限制，不是 FCM 连接本身。无法解决 Google 服务器不可达的问题。", 12, false, muted);
        androidExtra.addView(explanation);
        androidExtra.addView(text("本应用后台限制", 14, true, ink));
        androidExtra.addView(text("减少系统休眠和后台运行限制，可能提高监控存活率；不会保证永远运行。", 12, false, muted));
        line(androidExtra, "放宽后台限制", "仅修改并回读可验证的 Android 策略", this::optimize);
        line(androidExtra, "恢复原有后台设置", "只撤销本应用记录的修改", this::restoreOwn);
        LinearLayout divider = new LinearLayout(this);
        divider.setMinimumHeight(dp(12));
        androidExtra.addView(divider);
        androidExtra.addView(text("Google 推送服务（GMS）", 14, true, ink));
        androidExtra.addView(text("休眠白名单：允许系统休眠时 Google 推送继续工作。如果上面已显示 GMS 在白名单中，就不必重复添加。", 12, false, muted));
        line(androidExtra, "添加休眠豁免", "GMS 未列入白名单时才添加", () -> gmsPolicy(true));
        line(androidExtra, "撤销休眠豁免", "仅撤销本应用添加的 GMS 规则", () -> gmsPolicy(false));
        androidExtra.addView(text("GMS 后台流量", 14, true, ink));
        androidExtra.addView(text("只影响 Android 省流量限制，不改变 DNS、代理、VPN 或网络路径。", 12, false, muted));
        line(androidExtra, "允许后台计量流量", "回读并记录改动", () -> gmsNetwork(true));
        line(androidExtra, "撤销后台流量放行", "仅撤销本应用添加的规则", () -> gmsNetwork(false));
        androidExtra.addView(text("“恢复”仅撤销本应用记录的修改，不会重置系统或更改其他应用的权限。", 12, false, muted));
        policy = text("", 12, false, muted);
        policy.setTextIsSelectable(true);
        androidExtra.addView(policy);

        renderPolicy();
    }

    private String intervalLabel() {
        return "唤醒兜底巡检：" + prefs.watchdogMinutes() + " 分钟";
    }

    private void chooseInterval() {
        String[] choices = new String[10];
        for (int i = 0; i < choices.length; i++) choices[i] = (i + 1) + " 分钟";
        new android.app.AlertDialog.Builder(this)
                .setTitle("唤醒兜底巡检间隔")
                .setSingleChoiceItems(choices, prefs.watchdogMinutes() - 1, (dialog, index) -> {
                    int minutes = index + 1;
                    if (minutes != prefs.watchdogMinutes()) {
                        prefs.watchdogMinutes(minutes);
                        MonitorWatchdog.reschedule(this);
                        intervalButton.setText(intervalLabel());
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void syncWakeChoice() {
        if (wakeChoices == null) return;
        syncingWakeChoices = true;
        try {
            wakeChoices.check(AppPrefs.WAKE_CONTINUOUS.equals(prefs.wakeMode())
                    ? continuousChoiceId : smartChoiceId);
        } finally { syncingWakeChoices = false; }
    }
    private void applyWakeMode(String mode) {
        if (mode.equals(prefs.wakeMode())) { syncWakeChoice(); return; }
        prefs.wakeMode(mode);
        FcmMonitorService.wakeModeChanged(this);
        syncWakeChoice();
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
        syncWakeChoice();
        systemStates.setText("后台运行：" + (!PrivilegeAccess.readyForUserAction(this) ? "需要授权" : FcmMonitorService.serviceStatus(this))
                + "\nFCM 监控：" + (prefs.monitorEnabled() ? "已开启" : "未开启")
                + "\n应用守护：" + (prefs.guardEnabled() ? "已开启" : "未开启")
                + "\n熄屏方式：" + (AppPrefs.WAKE_CONTINUOUS.equals(prefs.wakeMode()) ? "持续唤醒" : "智能省电")
                + "\nCPU 唤醒锁：" + (FcmMonitorService.isCpuWakeLockHeld() ? "当前持有" : "未持有")
                + "\n累计持续持锁：" + MonitorStatus.duration(FcmMonitorService.continuousWakeMs(this) / 1000)
                + "\n累计确认窗口持锁：" + MonitorStatus.duration(FcmMonitorService.confirmationWakeMs(this) / 1000)
                + "\n累计事务持锁：" + MonitorStatus.duration(FcmMonitorService.transactionWakeMs(this) / 1000));
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
