package top.zesun.berryfcm;

import android.Manifest;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

public final class AuthActivity extends Ui {
    private static final String[] MODES =
            {AppPrefs.WIRELESS, AppPrefs.ROOT, AppPrefs.SHIZUKU, AppPrefs.STATIC, AppPrefs.EXTERNAL};
    private static final String[] LABELS = {"无线 ADB", "Root", "Shizuku", "ADB TCP", "电脑 USB"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout tabs, instructions;
    private TextView status, detail, errorSummary, errorLog;
    private LinearLayout failurePanel;
    private ImageButton stateToggle;
    private TextView autoStartRow, associatedRow, highPowerRow, lockRow, batteryRow, notificationRow, monitorNotificationRow, restrictionRow, tileRow;
    private android.widget.Button removeTileButton;
    private String selected;
    private boolean checking, tileRequestPending;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean lastPairing, lastReady, lastStarting;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!checking && AppPrefs.WIRELESS.equals(selected)) {
                boolean pairing = AdbPairingService.isPairingActive();
                boolean ready = PrivKitShell.isConnected();
                boolean starting = PrivKitShell.isStarting();
                if (pairing != lastPairing || ready != lastReady || starting != lastStarting) {
                    lastPairing = pairing;
                    lastReady = ready;
                    lastStarting = starting;
                    renderInstructions();
                    renderStatus();
                } else if (pairing) renderStatus();
            }
            if (!checking) renderStatus();
            ui.postDelayed(this, 2000);
        }
    };
    private boolean automaticCheckDone;
    private boolean startAfterSettings;
    private String selectedFailure = "";
    private boolean leftForSettings;
    private int pendingPairPort = -1;
    private static final int SHIZUKU_PERMISSION = 130;
    private static final int LOCAL_NETWORK_PERMISSION = 131;
    private int pendingNetworkAction; // 1 pairing, 2 connection
    private int pendingNetworkPairPort;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission = (code, grant) -> {
        if (code != SHIZUKU_PERMISSION) return;
        runOnUiThread(() -> {
            if (grant == PackageManager.PERMISSION_GRANTED && AppPrefs.SHIZUKU.equals(selected)) checkShell();
            else if (AppPrefs.SHIZUKU.equals(selected)) error("Shizuku 权限未授予；原连接方式不变");
        });
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selected = prefs.authTab();
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
        title("授权管理", "选择方式，管理启停");

        LinearLayout card = card("授权状态", null);
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(stateRow);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        stateRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        status = text("已停止", 21, true, ink);
        labels.addView(status);
        detail = text("", 12, false, muted);
        detail.setPadding(0, dp(4), 0, 0);
        labels.addView(detail);

        stateToggle = new ImageButton(this);
        stateToggle.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        stateToggle.setPadding(dp(13), dp(13), dp(13), dp(13));
        GradientDrawable toggleSurface = new GradientDrawable();
        toggleSurface.setColor(surface);
        toggleSurface.setCornerRadius(dp(14));
        stateToggle.setBackground(toggleSurface);
        stateToggle.setOnClickListener(v -> {
            if (checking || PrivKitShell.isStarting() || AdbPairingService.isPairingActive()) return;
            if (isSelectedRunning()) stopSelectedService();
            else startSelectedService();
        });
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(dp(52), dp(52));
        toggleSize.leftMargin = dp(12);
        stateRow.addView(stateToggle, toggleSize);

        failurePanel = new LinearLayout(this);
        failurePanel.setOrientation(LinearLayout.VERTICAL);
        failurePanel.setVisibility(View.GONE);
        card.addView(failurePanel);
        errorSummary = text("", 13, false, danger);
        errorSummary.setPadding(0, dp(10), 0, dp(4));
        errorSummary.setMaxLines(2);
        errorSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        failurePanel.addView(errorSummary);
        LinearLayout logPanel = disclosure(failurePanel, "查看完整日志", false);
        errorLog = text("", 12, false, muted);
        errorLog.setTextIsSelectable(true);
        logPanel.addView(errorLog);
        button(logPanel, "复制日志", () -> {
            android.content.ClipboardManager clipboard =
                    getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "BerryFCM 授权日志", errorLog.getText()));
                error("日志已复制");
            }
        }, false);

        LinearLayout chooser = card(null, null);
        LinearLayout choices = disclosure(chooser, "授权方式与使用说明", false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.VERTICAL);
        choices.addView(tabs);
        instructions = new LinearLayout(this);
        instructions.setOrientation(LinearLayout.VERTICAL);
        instructions.setPadding(dp(2), dp(12), dp(2), 0);
        choices.addView(instructions);
        renderTabs();
        renderInstructions();
        renderStatus();
        createAuxiliaryPermissions();
    }

    private TextView permissionRow(LinearLayout parent, String name, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        row.setMinimumHeight(dp(52));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(name, 14, true, ink));
        TextView value = text("正在读取", 12, false, muted);
        value.setPadding(0, dp(2), 0, 0);
        words.addView(value);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 22, false, muted);
        row.addView(arrow);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x220888ff), null, null));
        row.setOnClickListener(v -> click.run());
        row.setFocusable(true);
        parent.addView(row);
        return value;
    }

    private void permissionText(TextView row, String name, String state) {
        row.setText(state);
        row.setTextColor(state.startsWith("已开启") || state.startsWith("已添加")
                || state.startsWith("无后台限制") ? success : muted);
    }

    private void createAuxiliaryPermissions() {
        LinearLayout permissionCard = card(null, null);
        LinearLayout auxiliary = disclosure(permissionCard, "后台运行权限", false);
        autoStartRow = permissionRow(auxiliary, "自启动", () -> BackgroundSettings.autostart(this));
        associatedRow = permissionRow(auxiliary, "关联启动", () -> BackgroundSettings.associatedStart(this));
        highPowerRow = permissionRow(auxiliary, "允许后台高耗电", () -> BackgroundSettings.powerSettings(this));
        lockRow = permissionRow(auxiliary, "锁定后台", this::openRecentsForLock);
        batteryRow = permissionRow(auxiliary, "忽略电池优化", () -> {
            if (BackgroundSettings.batteryExempt(this)) BackgroundSettings.batteryList(this);
            else BackgroundSettings.requestBattery(this);
        });
        restrictionRow = permissionRow(auxiliary, "Android 后台限制", () -> BackgroundSettings.details(this));
        notificationRow = permissionRow(auxiliary, "通知权限", () -> BackgroundSettings.notifications(this));
        button(auxiliary, "刷新权限状态", this::refreshAuxiliaryPermissions, false);
        LinearLayout notificationCard = card(null, null);
        LinearLayout display = disclosure(notificationCard, "通知与快捷功能", false);
        monitorNotificationRow = permissionRow(display, "运行通知设置", () -> BackgroundSettings.monitorNotifications(this, prefs.quietMonitor()));
        TextView monitorTip = text("运行中的监控需要显示系统通知；它不是快捷设置开关。", 12, false, muted);
        monitorTip.setPadding(0, dp(6), 0, dp(6));
        display.addView(monitorTip);
        android.widget.Switch quiet = new android.widget.Switch(this);
        quiet.setText("运行通知静音");
        quiet.setTextColor(ink);
        quiet.setMinHeight(dp(48));
        quiet.setChecked(prefs.quietMonitor());
        display.addView(quiet);
        quiet.setOnCheckedChangeListener((v, enabled) -> {
            if (bindingSwitches()) return;
            prefs.quietMonitor(enabled);
            // Update an already running foreground notification, never start or
            // stop a task merely because the notification preference changed.
            FcmMonitorService.refreshDisplay(this);
            refreshAuxiliaryPermissions();
        });
        TextView quietTip = text("仅切换通知提醒方式，监控和保活不会停止。", 12, false, muted);
        display.addView(quietTip);
        tileRow = permissionRow(display, "快捷设置开关（非通知）", this::addTile);
        removeTileButton = button(display, "移除 FCM 快捷磁贴", () -> {
            if (tileRequestPending) { error("请先完成系统的磁贴添加确认"); return; }
            FcmTileService.setOffered(this, false);
            refreshAuxiliaryPermissions();
        }, false);
        android.widget.Switch hide = new android.widget.Switch(this);
        hide.setText("隐藏最近任务卡片");
        hide.setTextColor(ink);
        hide.setMinHeight(dp(48));
        hide.setChecked(prefs.hideRecents());
        display.addView(hide);
        hide.setOnCheckedChangeListener((v, enabled) -> {
            if (bindingSwitches()) return;
            if (RecentTasks.apply(this, enabled)) prefs.hideRecents(enabled);
            else {
                error("系统未确认设置，请在最近任务中确认");
                syncChecked(hide, prefs.hideRecents());
            }
        });
    }

    private void refreshAuxiliaryPermissions() {
        if (autoStartRow == null || isDestroyed() || isFinishing()) return;
        // OriginOS does not expose a verified public state API for these switches.
        permissionText(autoStartRow, "自启动", "需在系统确认");
        permissionText(associatedRow, "关联启动", "需在系统确认");
        permissionText(highPowerRow, "允许后台高耗电", "需在系统确认");
        permissionText(lockRow, "锁定后台", "需在最近任务确认");
        permissionText(batteryRow, "忽略电池优化", BackgroundSettings.batteryExempt(this) ? "已开启" : "未开启");
        permissionText(restrictionRow, "Android 后台限制", BackgroundSettings.backgroundRestriction(this));
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        permissionText(notificationRow, "通知权限", manager == null ? "无法读取" :
                manager.areNotificationsEnabled() ? "已开启" : "未开启");
        permissionText(monitorNotificationRow, "运行通知设置", BackgroundSettings.monitorNotificationStatus(this, prefs.quietMonitor()));
        permissionText(tileRow, "FCM 快捷磁贴", FcmTileService.displayStatus(this));
        removeTileButton.setVisibility(FcmTileService.offered(this) ? View.VISIBLE : View.GONE);
    }

    private void openRecentsForLock() {
        if (prefs.hideRecents()) {
            error("请先关闭隐藏最近任务卡片，再在最近任务中锁定");
            return;
        }
        android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("锁定后台")
                .setMessage("进入最近任务，在 BerryFCM 卡片菜单中选择锁定。仅由你操作，不修改系统锁定状态。若系统不支持自动跳转，请手动打开最近任务。")
                .setNegativeButton("取消", null);
        if (PrivilegeAccess.readyForUserAction(this)) {
            dialog.setPositiveButton("打开最近任务", (d, w) -> worker.execute(() -> {
                try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                    shell.execute("input keyevent KEYCODE_APP_SWITCH");
                } catch (Exception ex) {
                    runOnUiThread(() -> error("无法跳转，请手动打开系统最近任务"));
                }
            }));
        } else dialog.setPositiveButton("知道了", null);
        dialog.show();
    }

    private void addTile() {
        if (tileRequestPending) return;
        if (!PrivilegeAccess.readyForUserAction(this)) {
            error("请先完成上方核心授权，再添加磁贴");
            return;
        }
        boolean alreadyOffered = FcmTileService.offered(this);
        FcmTileService.setOffered(this, true);
        if (Build.VERSION.SDK_INT < 33) {
            error("请在系统下拉快捷栏的编辑页面手动添加 BerryFCM");
            refreshAuxiliaryPermissions();
            return;
        }
        StatusBarManager status = getSystemService(StatusBarManager.class);
        if (status == null) { error("请在系统下拉快捷栏的编辑页面手动添加 BerryFCM"); refreshAuxiliaryPermissions(); return; }
        tileRequestPending = true;
        try { status.requestAddTileService(
                new ComponentName(this, FcmTileService.class),
                "BerryFCM 监控",
                Icon.createWithResource(this, R.drawable.ic_bf_fcm),
                getMainExecutor(),
                result -> {
                    tileRequestPending = false;
                    boolean added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED;
                    if (!added && !alreadyOffered) FcmTileService.setOffered(this, false);
                    if (added && FcmTileService.offered(this)) FcmTileService.noteAdded(this, true);
                    error(added ? "磁贴已添加" : "未添加磁贴");
                    refreshAuxiliaryPermissions();
                });
        } catch (RuntimeException ex) {
            tileRequestPending = false;
            if (!alreadyOffered) FcmTileService.setOffered(this, false);
            error("无法打开系统磁贴添加窗口");
        }
    }

    private void renderTabs() {
        tabs.removeAllViews();
        LinearLayout group = null;
        for (int i = 0; i < MODES.length; i++) {
            if (i == 0 || i == 3) {
                group = new LinearLayout(this);
                group.setOrientation(LinearLayout.HORIZONTAL);
                tabs.addView(group, new LinearLayout.LayoutParams(-1, -2));
            }
            final String mode = MODES[i];
            boolean active = mode.equals(selected);
            TextView tab = text(LABELS[i], 13, active, active ? 0xffffffff : ink);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), dp(8), dp(4), dp(8));
            tab.setMinHeight(dp(44));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? 0xff365ed6 : (surface == 0xfff4f6fa ? 0xffebeff8 : 0xff293650));
            bg.setCornerRadius(dp(12));
            tab.setBackground(bg);
            tab.setFocusable(true);
            tab.setContentDescription(LABELS[i] + (active ? "，当前选中" : "，点击切换"));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            group.addView(tab, params);
            tab.setOnClickListener(v -> {
                if (checking || AdbPairingService.isPairingActive() || PrivKitShell.isStarting()) {
                    error("当前授权正在进行，请先完成或取消");
                    return;
                }
                if (mode.equals(selected)) return;
                selected = mode;
                selectedFailure = "";
                prefs.authTab(mode);
                // Browsing connection methods must not interrupt the active monitor.
                renderTabs();
                renderInstructions();
                renderStatus();
            });
        }
    }

    private void renderInstructions() {
        instructions.removeAllViews();
        if (AppPrefs.EXTERNAL.equals(selected)) usb();
        else if (AppPrefs.WIRELESS.equals(selected)) wireless();
        else if (AppPrefs.ROOT.equals(selected)) root();
        else if (AppPrefs.SHIZUKU.equals(selected)) shizuku();
        else tcp();
    }

    private void startSelectedService() {
        if (checking) return;
        if (AppPrefs.WIRELESS.equals(selected)) {
            if (prefs.pairingRejected() || (!prefs.adbPaired() && !PrivKitShell.hasStoredIdentity(this)))
                beginPair(0);
            else startWireless();
        } else if (AppPrefs.SHIZUKU.equals(selected)) authorizeShizuku();
        else if (!AppPrefs.EXTERNAL.equals(selected)) checkShell();
    }

    private boolean isSelectedRunning() {
        if (!selected.equals(prefs.mode()) || prefs.servicePaused(selected)) return false;
        if (AppPrefs.WIRELESS.equals(selected)) return PrivKitShell.isConnected();
        if (AppPrefs.SHIZUKU.equals(selected)) return ShizukuShell.isRunning();
        return PrivilegeAccess.readyForUserAction(this);
    }

    private void step(String label, String description) {
        TextView heading = text(label, 14, true, ink);
        heading.setPadding(0, dp(11), 0, dp(3));
        instructions.addView(heading);
        instructions.addView(text(description, 12, false, muted));
    }

    private void wireless() {
        if (AdbPairingService.isPairingActive()) {
            step("正在配对", "在无线调试页面打开配对码，再在通知栏输入六位数字。");
            button(instructions, "打开无线调试", this::openWirelessDebugging, false);
            button(instructions, "取消配对", () -> AdbPairingService.stop(this), false);
            return;
        }
        boolean paired = (prefs.adbPaired() || PrivKitShell.hasStoredIdentity(this))
                && !prefs.pairingRejected();
        if (!paired) button(instructions, "首次配对", () -> beginPair(0), false);
        LinearLayout help = disclosure(instructions, paired ? "重新配对" : "配对说明", false);
        wirelessSteps(help);
        LinearLayout advanced = disclosure(instructions, "手动连接端口", false);
        connectPortFallback(advanced);
    }

    private void startWireless() {
        if (checking || AdbPairingService.isPairingActive() || PrivKitShell.isStarting()) return;
        prefs.wirelessConnectPort(0);
        if (PrivKitShell.wirelessDebuggingState(this) == 0) openWirelessForStart();
        else checkShell();
    }

    private void stopSelectedService() {
        if (checking || PrivKitShell.isStarting() || AdbPairingService.isPairingActive()) return;
        final String mode = selected;
        if (AppPrefs.EXTERNAL.equals(mode)) return;
        checking = true;
        renderStatus();
        worker.execute(() -> {
            try {
                prefs.servicePaused(mode, true);
                if (AppPrefs.WIRELESS.equals(mode)) PrivKitShell.stopManually(this);
                else if (AppPrefs.SHIZUKU.equals(mode)) ShizukuShell.stopBinding();
                if (mode.equals(prefs.mode())) FcmMonitorService.stop(this);
                runOnUiThread(() -> {
                    checking = false;
                    if (isDestroyed() || isFinishing()) return;
                    selectedFailure = "";
                    renderStatus();
                    renderInstructions();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    checking = false;
                    if (!isDestroyed()) error("停止失败：" + failure.getMessage());
                });
            }
        });
    }

    private void restartSelectedService() {
        if (checking || PrivKitShell.isStarting() || AdbPairingService.isPairingActive()) return;
        final String mode = selected;
        if (AppPrefs.EXTERNAL.equals(mode)) return;
        checking = true;
        renderStatus();
        worker.execute(() -> {
            try {
                prefs.servicePaused(mode, true);
                if (AppPrefs.WIRELESS.equals(mode)) PrivKitShell.stopManually(this);
                else if (AppPrefs.SHIZUKU.equals(mode)) ShizukuShell.stopBinding();
                if (mode.equals(prefs.mode())) FcmMonitorService.stop(this);
                runOnUiThread(() -> {
                    checking = false;
                    if (isDestroyed() || isFinishing()) return;
                    prefs.servicePaused(mode, false);
                    startSelectedService();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    checking = false;
                    if (!isDestroyed()) error("重启失败：" + failure.getMessage());
                });
            }
        });
    }

    private void connectPortFallback(LinearLayout parent) {
        stepIn(parent, "无线调试连接端口", "在系统无线调试首页查看 IP 地址和端口。只填写端口数字，不是六位配对码页面的端口。");
        EditText field = field(parent, "例如 39123", prefs.wirelessConnectPort() > 0 ? String.valueOf(prefs.wirelessConnectPort()) : "", true);
        button(parent, "使用连接端口启动服务", () -> {
            int port = parsePort(field.getText().toString());
            if (port == 0) { error("请输入有效连接端口"); return; }
            prefs.wirelessConnectPort(port);
            checkShell();
        }, true);
        button(parent, "恢复自动发现并启动", () -> {
            prefs.wirelessConnectPort(0);
            checkShell();
        }, false);
        button(parent, "打开无线调试设置", this::openWirelessForStart, false);
    }

    private void wirelessSteps(LinearLayout parent) {
        stepIn(parent, "配对", "打开系统的配对码页面，在通知栏输入六位数字。");
        button(parent, "开始配对", () -> beginPair(0), false);
        LinearLayout fallback = disclosure(parent, "无法自动发现？", false);
        stepFallback(fallback);
    }

    private void stepIn(LinearLayout parent, String label, String description) {
        TextView heading = text(label, 14, true, ink);
        heading.setPadding(0, dp(10), 0, dp(3));
        parent.addView(heading);
        parent.addView(text(description, 12, false, muted));
    }

    private void stepFallback(LinearLayout fallback) {
        TextView tip = text("正常配对无需端口。自动发现失败时可直接在通知栏输入配对端口:六位配对码，不必退出系统配对页面。",
                12, false, muted);
        tip.setPadding(0, dp(5), 0, dp(8));
        fallback.addView(tip);
        EditText pairingPort = field(fallback, "系统配对页面显示的端口", "", true);
        button(fallback, "指定端口开始配对", () -> {
            int port = parsePort(pairingPort.getText().toString());
            if (port == 0) { error("请输入有效配对端口"); return; }
            beginPair(port);
        }, false);
    }

    private void root() {
        step("Root 授权", "安装并打开 KernelSU、Magisk 或其他 Root 管理器，点击上方启动；如弹出授权请求，允许 BerryFCM。");
        if (!prefs.shellError().isEmpty() && AppPrefs.ROOT.equals(prefs.shellMode())) {
            LinearLayout log = disclosure(instructions, "错误详情", false);
            log.addView(text(prefs.shellError(), 12, false, muted));
        }
    }

    private void shizuku() {
        boolean granted = ShizukuShell.authorizedUid() >= 0;
        step("Shizuku " + (granted ? "已授权" : "首次授权"),
                granted ? "点上方启停图标即可使用或停止当前连接；不会关闭 Shizuku。"
                        : "先打开 Shizuku 并启动它，再返回点上方启动，允许 BerryFCM 授权。");
        button(instructions, "打开 Shizuku", () -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch == null) error("未安装 Shizuku，请先安装并启动");
            else startActivity(launch);
        }, false);
    }

    private void authorizeShizuku() {
        if (checking) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.getVersion() < 13) {
                error(ShizukuShell.readiness());
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                checkShell();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                error("请在 Shizuku 的应用授权列表中允许 BerryFCM");
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION);
            }
        } catch (RuntimeException ex) { error(ShizukuShell.readiness()); }
    }

    private void tcp() {
        step("ADB TCP", "需要电脑先通过 USB 授权并启用 ADB TCP；填写实际连接端口，再点“保存并启动”。");
        EditText host = field(instructions, "ADB 主机地址", prefs.host(), false);
        EditText port = field(instructions, "ADB 端口", String.valueOf(prefs.staticPort()), true);
        button(instructions, "保存并启动", () -> {
            String address = host.getText().toString().trim();
            int number = parsePort(port.getText().toString());
            if (address.isEmpty() || number == 0) { error("主机地址或端口无效"); return; }
            checkShell(address, number);
        }, true);
    }

    private void usb() {
        step("电脑 USB", "先在电脑上授权 USB 调试，再执行 adb tcpip 5555；随后切换到 ADB TCP。手机应用无法自行授予 USB Shell 权限。");
        button(instructions, "切换到 ADB TCP", () -> {
            selected = AppPrefs.STATIC;
            prefs.authTab(selected);
            renderTabs();
            renderInstructions();
            renderStatus();
        }, true);
    }

    private EditText field(LinearLayout parent, String hint, String initial, boolean numeric) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(ink);
        field.setHintTextColor(muted);
        field.setHint(hint);
        field.setText(initial);
        if (numeric) field.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(field);
        return field;
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) { return 0; }
    }

    private boolean requestLocalNetworkIfNeeded(int action, int port) {
        if (Build.VERSION.SDK_INT < 37 || checkSelfPermission(
                "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED) return false;
        pendingNetworkAction = action;
        pendingNetworkPairPort = port;
        requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"}, LOCAL_NETWORK_PERMISSION);
        return true;
    }

    private void beginPair(int manualPort) {
        if (checking || AdbPairingService.isPairingActive() || PrivKitShell.isStarting()) return;
        if (requestLocalNetworkIfNeeded(1, manualPort)) return;
        pendingPairPort = manualPort;
        if (Build.VERSION.SDK_INT < 30) {
            error("系统不支持 Android 11 无线 ADB 配对");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 120);
            return;
        }
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            error("请先开启 BerryFCM 通知权限，才能从通知栏输入配对码");
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        android.app.NotificationChannel channel = manager.getNotificationChannel("berryfcm_pairing");
        if (channel != null && channel.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
            error("无线 ADB 配对通知已关闭，请开启这个通知类别");
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "berryfcm_pairing"));
            return;
        }
        try {
            AdbPairingService.begin(this, manualPort);
            renderStatus();
            pendingPairPort = -1;
            openWirelessDebugging();
        } catch (RuntimeException e) {
            selectedFailure = String.valueOf(e.getMessage());
            renderStatus();
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCAL_NETWORK_PERMISSION) {
            int requested = pendingNetworkAction;
            int pairPort = pendingNetworkPairPort;
            pendingNetworkAction = 0;
            pendingNetworkPairPort = 0;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requested == 1) beginPair(pairPort);
                else if (requested == 2) checkShell();
            } else {
                selectedFailure = "Android 17 本地网络权限未授予";
                renderStatus();
                error("请在应用权限中允许本地网络访问");
            }
            return;
        }
        if (requestCode != 120) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            pendingPairPort = -1;
            error("配对通知所需权限未授予，无法自动继续");
            return;
        }
        if (pendingPairPort >= 0) beginPair(pendingPairPort);
    }

    private void openWirelessForStart() {
        if (checking) return;
        startAfterSettings = true;
        leftForSettings = false;
        openWirelessDebugging();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
        if (startAfterSettings) leftForSettings = true;
    }

    private void openWirelessDebugging() {
        // Some OEM Settings export this shortcut; fall back to the official
        // Developer Options intent with the hint used by Shizuku.
        try {
            Intent direct = new Intent(Intent.ACTION_MAIN);
            direct.setClassName("com.android.settings",
                    "com.android.settings.Settings$WirelessDebuggingActivity");
            startActivity(direct);
            return;
        } catch (RuntimeException ignored) { }
        try {
            Intent developer = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            developer.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
            Bundle arguments = new Bundle();
            arguments.putString(":settings:fragment_args_key", "toggle_adb_wireless");
            developer.putExtra(":settings:show_fragment_args", arguments);
            startActivity(developer);
        } catch (RuntimeException error) {
            error("无法直接打开无线调试，请在系统设置中进入开发者选项");
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) { }
        }
    }

    private void checkShell() {
        checkShell(prefs.host(), prefs.staticPort());
    }

    private void checkShell(String host, int port) {
        if (checking || AdbPairingService.isPairingActive() || PrivKitShell.isStarting()
                || AppPrefs.EXTERNAL.equals(selected)) return;
        final String candidate = selected;
        if (AppPrefs.WIRELESS.equals(candidate) && requestLocalNetworkIfNeeded(2, 0)) return;
        prefs.servicePaused(candidate, false);
        selectedFailure = "";
        if (AppPrefs.WIRELESS.equals(candidate)) {
            if (AdbPairingService.isPairingActive() || PrivKitShell.isStarting()) return;
            automaticCheckDone = true;
        }
        checking = true;
        renderStatus();
        worker.execute(() -> {
            try {
                String identity = PrivilegeAccess.probe(this, candidate, host, port);
                prefs.activateMode(candidate, host, port, identity);
                if (!AppPrefs.WIRELESS.equals(candidate)) PrivKitShell.stop();
                prefs.enableMonitorOnFirstAuthorization();
                runOnUiThread(() -> {
                    checking = false;
                    if (isDestroyed() || isFinishing()) return;
                    selectedFailure = "";
                    FcmMonitorService.resumeIfNeeded(this);
                    renderStatus();
                    renderInstructions();
                });
            } catch (Exception ex) {
                String message = PrivKitShell.errorChain(ex);
                runOnUiThread(() -> {
                    checking = false;
                    if (isDestroyed() || isFinishing()) return;
                    selectedFailure = message;
                    renderStatus();
                    renderInstructions();
                });
            }
        });
    }

    private String methodName(String mode) {
        if (AppPrefs.WIRELESS.equals(mode)) return "无线 ADB";
        if (AppPrefs.SHIZUKU.equals(mode)) return "Shizuku";
        if (AppPrefs.ROOT.equals(mode)) return "Root";
        if (AppPrefs.STATIC.equals(mode)) return "ADB TCP";
        return "电脑 USB";
    }

    private void renderStatus() {
        if (status == null) return;
        detail.setText("来源：" + methodName(selected));
        if (AppPrefs.EXTERNAL.equals(selected)) {
            status.setText("不可直接启动");
            status.setTextColor(muted);
            stateToggle.setImageResource(android.R.drawable.ic_media_play);
            stateToggle.setEnabled(false);
            failurePanel.setVisibility(View.GONE);
            return;
        }
        boolean busy = checking || PrivKitShell.isStarting()
                || AdbPairingService.isPairingActive();
        boolean running = isSelectedRunning();
        String failure = selectedFailure;
        if (failure.isEmpty() && selected.equals(prefs.mode()) && !prefs.servicePaused(selected)) {
            if (AppPrefs.WIRELESS.equals(selected)) failure = PrivKitShell.lastStartupError(this);
            else if (selected.equals(prefs.shellMode())) failure = prefs.shellError();
        }
        String state = busy ? "处理中" : running ? "已启动"
                : !failure.isEmpty() ? "启动失败" : "已停止";
        status.setText(state);
        status.setTextColor(running ? success : "启动失败".equals(state) ? danger
                : busy ? warning : muted);
        stateToggle.setImageResource(running
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        stateToggle.setColorFilter(running ? success : accent);
        stateToggle.setEnabled(!busy);
        stateToggle.setContentDescription((running ? "停止" : "启动") + methodName(selected));
        boolean failed = "启动失败".equals(state);
        failurePanel.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (failed) {
            String singleLine = failure.replace('\n', ' ').replace('\r', ' ').trim();
            errorSummary.setText(singleLine);
            errorLog.setText(failure + (AppPrefs.WIRELESS.equals(selected)
                    ? "\n\n" + PrivKitShell.diagnostic(this) : ""));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAuxiliaryPermissions();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
        if (startAfterSettings && leftForSettings) {
            startAfterSettings = false;
            leftForSettings = false;
            if (!checking && AppPrefs.WIRELESS.equals(selected) && !PrivKitShell.isConnected()) {
                prefs.wirelessConnectPort(0);
                checkShell();
                return;
            }
        }
        if (!automaticCheckDone && !checking && AppPrefs.WIRELESS.equals(selected)) {
            automaticCheckDone = true;
            PrivKitShell.recoverOnForeground(this);
        }
        if (!checking) {
            renderStatus();
            if (instructions != null && (AppPrefs.WIRELESS.equals(selected) || AppPrefs.SHIZUKU.equals(selected)))
                renderInstructions();
        }
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(refresh);
        Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        worker.shutdownNow();
        super.onDestroy();
    }
}
