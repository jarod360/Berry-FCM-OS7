package top.zesun.berryfcm;

import android.os.Bundle;
import android.os.Build;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackgroundActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView policy, systemStates, tileState, autoStartState;
    private android.widget.Button addTileButton, removeTileButton;

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

        LinearLayout android = card("系统后台权限", "优先完成系统电池授权，再按需使用 Root / ADB 修改策略。");
        systemStates = text("", 13, false, muted);
        android.addView(systemStates);
        button(android, "忽略电池优化", () -> {
            if (BackgroundSettings.batteryExempt(this)) BackgroundSettings.batteryList(this);
            else BackgroundSettings.requestBattery(this);
        }, true);
        button(android, "打开应用信息（后台耗电）", () -> BackgroundSettings.details(this), false);
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

        LinearLayout vendor = card("OriginOS 后台权限", "逐项设置；返回页面重新读取系统可提供的状态。");
        autoStartState = text("", 13, false, muted);
        vendor.addView(autoStartState);
        line(vendor, "自启动", "打开系统自启动管理", () -> BackgroundSettings.autostart(this));
        line(vendor, "关联启动", "需在系统启动管理中确认；暂无法独立读取", () -> BackgroundSettings.autostart(this));
        line(vendor, "允许后台高耗电", "需在系统后台耗电管理中确认；暂无法独立读取", () -> BackgroundSettings.powerSettings(this));
        line(vendor, "锁定后台", "需在最近任务中锁定 BerryFCM；暂无法独立读取", () -> {
            new android.app.AlertDialog.Builder(this).setTitle("锁定后台")
                    .setMessage("先保持 BerryFCM 的任务卡片可见，然后进入系统最近任务，在 BerryFCM 卡片菜单中选择锁定。系统锁定不同于 Android 屏幕固定。")
                    .setPositiveButton("知道了", null).show();
        });
        LinearLayout vendorExtra = disclosure(vendor, "最近任务显示", false);
        toggle(vendorExtra, "隐藏最近任务卡片", prefs.hideRecents(), value -> {
            if (RecentTasks.apply(this, value)) {
                prefs.hideRecents(value);
                error(value ? "已请求隐藏卡片；需要时可回到此处恢复" : "已请求显示任务卡片");
            } else error("系统未确认卡片操作，保持原设置");
        });
        line(vendorExtra, "说明", "隐藏最近任务卡片不隐藏后台进程，也不关闭前台服务通知。", null);
        LinearLayout display = card("通知与快捷磁贴", "显示方式由你选择，不影响已授权的后台监控。");
        line(display, "系统通知权限", "查看或修改 BerryFCM 通知权限", () -> BackgroundSettings.notifications(this));
        line(display, "监控通知类别", "调整监控通知的系统显示和声音", () -> BackgroundSettings.monitorNotifications(this, prefs.quietMonitor()));
        toggle(display, "监控通知低干扰模式", prefs.quietMonitor(), value -> {
            prefs.quietMonitor(value);
            syncService();
        });
        tileState = text("", 13, false, muted);
        display.addView(tileState);
        addTileButton = button(display, "添加 FCM 快捷磁贴", this::addTile, false);
        removeTileButton = button(display, "移除 FCM 快捷磁贴", () -> {
            FcmTileService.setOffered(this, false);
            renderPolicy();
        }, false);
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
        android.app.NotificationManager notifications =
                getSystemService(android.app.NotificationManager.class);
        boolean notificationEnabled = notifications != null && notifications.areNotificationsEnabled();
        systemStates.setText("后台运行：" + (!PrivilegeAccess.readyForUserAction(this) ? "需要授权" : FcmMonitorService.serviceStatus(this))
                + "\n忽略系统电池优化：" + (BackgroundSettings.batteryExempt(this) ? "已开启" : "未开启")
                + "\nAndroid 后台限制：" + BackgroundSettings.backgroundRestriction(this)
                + "\n通知权限：" + (notificationEnabled ? "已开启" : "未开启")
                + "\n监控通知类别：" + BackgroundSettings.monitorNotificationStatus(this, prefs.quietMonitor()));
        if (autoStartState != null) autoStartState.setText("自启动：" + BackgroundSettings.originAutoStartStatus(this));
        if (tileState != null) {
            tileState.setText("FCM 快捷磁贴：" + FcmTileService.displayStatus(this));
            removeTileButton.setVisibility(FcmTileService.offered(this) ? android.view.View.VISIBLE : android.view.View.GONE);
        }
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
    private void addTile() {
        if (!PrivilegeAccess.readyForUserAction(this)) {
            error("请先完成授权，再添加磁贴");
            go(AuthActivity.class);
            return;
        }
        boolean alreadyOffered = FcmTileService.offered(this);
        FcmTileService.setOffered(this, true);
        if (Build.VERSION.SDK_INT < 33) {
            error("请在系统下拉快捷栏的编辑页面手动添加 BerryFCM");
            renderPolicy();
            return;
        }
        StatusBarManager status = getSystemService(StatusBarManager.class);
        if (status == null) { error("请在系统下拉快捷栏的编辑页面手动添加 BerryFCM"); renderPolicy(); return; }
        try { status.requestAddTileService(
                new ComponentName(this, FcmTileService.class),
                "BerryFCM 监控",
                Icon.createWithResource(this, R.drawable.ic_bf_fcm),
                getMainExecutor(),
                result -> {
                    boolean added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED;
                    if (!added && !alreadyOffered) FcmTileService.setOffered(this, false);
                    if (added) FcmTileService.noteAdded(this, true);
                    error(added ? "磁贴已添加" : "未添加磁贴");
                    renderPolicy();
                });
        } catch (RuntimeException ex) {
            if (!alreadyOffered) FcmTileService.setOffered(this, false);
            error("无法打开系统磁贴添加窗口");
        }
    }
}
