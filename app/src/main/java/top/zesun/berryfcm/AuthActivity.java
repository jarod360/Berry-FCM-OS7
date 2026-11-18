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
    private TextView status, detail;
    private TextView autoStartRow, associatedRow, highPowerRow, lockRow, batteryRow, notificationRow, monitorNotificationRow, restrictionRow, tileRow;
    private android.widget.Button removeTileButton;
    private String selected;
    private boolean checking, tileRequestPending, auxiliaryReading, auxiliaryAgain;
    private final ExecutorService permissionWorker = Executors.newSingleThreadExecutor();
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
            ui.postDelayed(this, 1000);
        }
    };
    private boolean automaticCheckDone;
    private boolean startAfterSettings;
    private boolean leftForSettings;
    private int pendingPairPort = -1;
    private static final int SHIZUKU_PERMISSION = 130;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission = (code, grant) -> {
        if (code != SHIZUKU_PERMISSION) return;
        runOnUiThread(() -> {
            if (grant == PackageManager.PERMISSION_GRANTED && AppPrefs.SHIZUKU.equals(selected)) checkShell();
            else if (AppPrefs.SHIZUKU.equals(selected)) error("Shizuku 权限未授予；原连接方式不变");
        });
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selected = prefs.mode();
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
        title("授权管理", "核心授权与辅助后台权限");

        LinearLayout card = card("授权状态", null);
        status = text("检查状态中", 20, true, ink);
        card.addView(status);
        detail = text("", 12, false, muted);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, dp(5), 0, dp(1));
        card.addView(detail);

        LinearLayout chooser = card("授权方式", "无线 ADB 通常只需配对一次；选择其他方式不会立即切换。");
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.VERTICAL);
        chooser.addView(tabs);

        LinearLayout steps = card("操作步骤", null);
        instructions = new LinearLayout(this);
        instructions.setOrientation(LinearLayout.VERTICAL);
        steps.addView(instructions);
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
        LinearLayout auxiliary = card("辅助后台权限", "建议开启，由你在系统中选择。不影响核心授权或当前监控、守护任务的运行。");
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
        LinearLayout diagnostic = disclosure(auxiliary, "厂商权限检测诊断", false);
        TextView evidence = text("仅查看 BerryFCM 自身的系统 AppOps，不修改任何权限。", 12, false, muted);
        evidence.setTextIsSelectable(true);
        diagnostic.addView(evidence);
        button(diagnostic, "读取系统权限证据", () -> {
            if (!PrivilegeAccess.readyForUserAction(this)) {
                error("请先完成核心授权；辅助权限不影响监控");
                return;
            }
            evidence.setText("正在读取本应用 AppOps…");
            worker.execute(() -> {
                try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                    String result = shell.execute("cmd appops get top.zesun.berryfcm 2>&1");
                    runOnUiThread(() -> evidence.setText(result));
                } catch (Exception ex) {
                    runOnUiThread(() -> evidence.setText("读取失败：" + PrivKitShell.errorChain(ex)));
                }
            });
        }, false);

        LinearLayout display = card("通知与快捷磁贴", "独立显示选项，不控制后台监控和守护。");
        monitorNotificationRow = permissionRow(display, "监控通知类别", () -> BackgroundSettings.monitorNotifications(this, prefs.quietMonitor()));
        android.widget.Switch quiet = new android.widget.Switch(this);
        quiet.setText("监控通知低干扰模式");
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
        tileRow = permissionRow(display, "FCM 快捷磁贴", this::addTile);
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
        if (auxiliaryReading) auxiliaryAgain = true;
        else {
            auxiliaryReading = true;
            permissionText(autoStartRow, "自启动", "正在读取");
            readAutoStartPermission();
        }
        permissionText(associatedRow, "关联启动", "需在系统确认");
        permissionText(highPowerRow, "允许后台高耗电", "需在系统确认");
        permissionText(lockRow, "锁定后台", "需在最近任务确认");
        permissionText(batteryRow, "忽略电池优化", BackgroundSettings.batteryExempt(this) ? "已开启" : "未开启");
        permissionText(restrictionRow, "Android 后台限制", BackgroundSettings.backgroundRestriction(this));
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        permissionText(notificationRow, "通知权限", manager == null ? "无法读取" :
                manager.areNotificationsEnabled() ? "已开启" : "未开启");
        permissionText(monitorNotificationRow, "监控通知类别", BackgroundSettings.monitorNotificationStatus(this, prefs.quietMonitor()));
        permissionText(tileRow, "FCM 快捷磁贴", FcmTileService.displayStatus(this));
        removeTileButton.setVisibility(FcmTileService.offered(this) ? View.VISIBLE : View.GONE);
    }

    private void readAutoStartPermission() {
        permissionWorker.execute(() -> {
            String result;
            try { result = BackgroundSettings.originAutoStartStatus(this); }
            catch (RuntimeException error) { result = "无法读取"; }
            final String value = result;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) { auxiliaryReading = false; return; }
                if (auxiliaryAgain) {
                    auxiliaryAgain = false;
                    // One additional async read, not another full page refresh.
                    readAutoStartPermission();
                } else {
                    auxiliaryReading = false;
                    permissionText(autoStartRow, "自启动", value);
                }
            });
        });
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
                // Browsing connection methods must not interrupt the active monitor.
                renderTabs();
                renderInstructions();
                renderStatus();
            });
        }
    }

    private void renderInstructions() {
        instructions.removeAllViews();
        if (AppPrefs.WIRELESS.equals(selected)) wireless();
        else if (AppPrefs.ROOT.equals(selected)) root();
        else if (AppPrefs.SHIZUKU.equals(selected)) shizuku();
        else if (AppPrefs.STATIC.equals(selected)) tcp();
        else usb();
    }

    private void step(String label, String description) {
        TextView heading = text(label, 14, true, ink);
        heading.setPadding(0, dp(11), 0, dp(3));
        instructions.addView(heading);
        instructions.addView(text(description, 12, false, muted));
    }

    private void wireless() {
        if (AdbPairingService.isPairingActive()) {
            step("正在配对", "请在系统的无线调试页面打开配对码，再从通知栏输入六位数字。");
            button(instructions, "打开无线调试", this::openWirelessDebugging, true);
            button(instructions, "取消配对", () -> AdbPairingService.stop(this), false);
            return;
        }
        if (PrivKitShell.isStarting() || checking) {
            step("正在启动服务…", "请保持无线调试开启。");
            return;
        }
        if (PrivKitShell.isConnected()) {
            step("授权成功", "服务正在运行，可以使用 FCM 监控。");
            button(instructions, "返回 FCM 监控", () -> {
                if (!AppPrefs.WIRELESS.equals(prefs.mode())) {
                    checkShell();
                    return;
                }
                FcmMonitorService.resumeIfNeeded(this);
                go(MainActivity.class);
            }, true);
            return;
        }
        boolean previous = prefs.adbPaired() && !prefs.pairingRejected();
        if (previous) {
            step("已有配对记录", "先尝试启动服务。只有系统撤销授权时才需要重新配对。");
            button(instructions, "启动服务", this::startWireless, true);
            LinearLayout repair = disclosure(instructions, "重新配对", false);
            wirelessSteps(repair);
        } else {
            if (!prefs.pairingRejected())
                button(instructions, "检查已有授权", this::startWireless, false);
            wirelessSteps(instructions);
        }
        LinearLayout direct = disclosure(instructions, "高级：手动输入连接端口", false);
        connectPortFallback(direct);
        String error = PrivKitShell.lastStartupError(this);
        String pairing = prefs.pairingStatus();
        if (!error.isEmpty() || pairing.startsWith("配对失败") || pairing.startsWith("服务启动失败")) {
            LinearLayout diag = disclosure(instructions, "查看失败原因", false);
            TextView log = text((error.isEmpty() ? pairing : error), 12, false, muted);
            log.setTextIsSelectable(true);
            diag.addView(log);
            button(diag, "复制授权诊断", () -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BerryFCM 授权诊断",
                            prefs.pairingStatus() + "\n" + PrivKitShell.diagnostic(this)));
                    error("授权诊断已复制");
                }
            }, false);
        }
    }

    private void startWireless() {
        if (checking || AdbPairingService.isPairingActive() || PrivKitShell.isStarting()) return;
        prefs.wirelessConnectPort(0);
        if (PrivKitShell.wirelessDebuggingState(this) == 0) openWirelessForStart();
        else checkShell();
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
        stepIn(parent, "1 · 打开无线调试",
                "打开无线调试后，点击“使用配对码配对设备”。");
        button(parent, "开始配对并打开无线调试", () -> beginPair(0), true);
        stepIn(parent, "2 · 输入配对码",
                "保持配对码页面打开，下拉通知栏，在 BerryFCM 通知中输入六位数字。");
        stepIn(parent, "3 · 完成授权",
                "配对后自动启动服务并开启 FCM 监控。以后通常不必重新配对。");
        LinearLayout fallback = disclosure(parent, "端口无法发现？手动输入", false);
        stepFallback(fallback);
        button(fallback, "只打开无线调试设置", this::openWirelessDebugging, false);
        button(fallback, "取消配对通知", () -> {
            AdbPairingService.stop(this);
            status.setText("配对已取消");
            detail.setText("可重新开始。");
        }, false);
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
        step("申请 Root Shell",
                "系统 Root 管理器可能弹出授权请求。只有 Shell 执行 id 返回 uid=0 才算完成。");
        button(instructions, "申请并验证 Root Shell", this::checkShell, true);
    }

    private void shizuku() {
        step("独立 Shell 服务", "适合已安装并启动 Shizuku v13+ 的用户。通过官方 Binder 服务采样，不占用 BerryFCM 的 ADB 连接。");
        step("当前状态", ShizukuShell.readiness());
        button(instructions, "授权并使用 Shizuku", this::authorizeShizuku, true);
        button(instructions, "打开 Shizuku", () -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch == null) error("尚未安装 Shizuku；可直接使用 Root 或内置无线 ADB");
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
        step("验证已经开放的 ADB TCP 端口",
                "需已开启 adbd TCP 服务；仅填写地址不会启用系统无线调试。");
        EditText host = field(instructions, "ADB 主机地址", prefs.host(), false);
        EditText port = field(instructions, "ADB 端口", String.valueOf(prefs.staticPort()), true);
        button(instructions, "保存并验证 ADB Shell", () -> {
            String address = host.getText().toString().trim();
            int number = parsePort(port.getText().toString());
            if (address.isEmpty() || number == 0) { error("主机地址或端口无效"); return; }
            checkShell(address, number);
        }, true);
    }

    private void usb() {
        step("在电脑上初始化 ADB TCP", "USB 连接必须先经系统授权。手机 APK 本身不能授予持续 Shell 权限。");
        step("电脑执行", "adb tcpip 5555");
        step("返回 BerryFCM", "切换到 ADB TCP，填写本机 127.0.0.1 和实际开放的端口后验证。重启后可能失效。");
        button(instructions, "切换到 ADB TCP", () -> {
            selected = AppPrefs.STATIC;
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

    private void beginPair(int manualPort) {
        if (checking || AdbPairingService.isPairingActive() || PrivKitShell.isStarting()) return;
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
            status.setText("等待输入配对码");
            detail.setText("打开系统的配对码页面，再在通知栏输入六位数字。");
            pendingPairPort = -1;
            openWirelessDebugging();
        } catch (RuntimeException e) {
            status.setText("无法开始配对");
            detail.setText(String.valueOf(e.getMessage()));
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
        if (AppPrefs.WIRELESS.equals(candidate)) {
            if (AdbPairingService.isPairingActive() || PrivKitShell.isStarting()) return;
            automaticCheckDone = true;
        }
        checking = true;
        status.setText("正在连接并授权…");
        detail.setText(AppPrefs.WIRELESS.equals(candidate) ? "正在尝试启动服务，请保持无线调试开启。" : "正在检查授权，请按系统提示操作。");
        if (AppPrefs.WIRELESS.equals(candidate)) renderInstructions();
        worker.execute(() -> {
            try {
                String identity = PrivilegeAccess.probe(this, candidate, host, port);
                prefs.activateMode(candidate, host, port, identity);
                if (!AppPrefs.WIRELESS.equals(candidate)) PrivKitShell.stop();
                if (AppPrefs.WIRELESS.equals(candidate) || AppPrefs.SHIZUKU.equals(candidate)) prefs.enableMonitorOnFirstAuthorization();
                runOnUiThread(() -> {
                    checking = false;
                    FcmMonitorService.resumeIfNeeded(this);
                    if (isDestroyed() || isFinishing()) return;
                    renderStatus();
                    renderInstructions();
                });
            } catch (Exception ex) {
                String message = PrivKitShell.errorChain(ex);
                runOnUiThread(() -> {
                    checking = false;
                    if (isDestroyed() || isFinishing()) return;
                    boolean wireless = AppPrefs.WIRELESS.equals(candidate);
                    status.setText(wireless && prefs.pairingRejected() ? "授权已失效，请重新配对" : "连接失败");
                    status.setTextColor(danger);
                    detail.setText(wireless ? "请确认无线调试已开启，再试一次。\n具体原因：" + message : message);
                    renderInstructions();
                });
            }
        });
    }

    private void renderStatus() {
        if (status == null || checking) return;
        status.setTextColor(muted);
        if (AppPrefs.EXTERNAL.equals(selected)) {
            status.setText("电脑 USB · 初始化指引");
            detail.setText("这不是 APK 的授权模式。电脑启用 ADB TCP 后，回到 ADB TCP 标签连接验证。");
            return;
        }
        String mode = prefs.mode();
        if (!selected.equals(mode)) {
            boolean ready = PrivilegeAccess.readyForUserAction(this);
            status.setText("当前使用：" + (AppPrefs.ROOT.equals(mode) ? "Root" : AppPrefs.SHIZUKU.equals(mode) ? "Shizuku" : AppPrefs.STATIC.equals(mode) ? "ADB TCP" : "无线 ADB"));
            status.setTextColor(ready ? success : warning);
            detail.setText((ready ? "当前方式可用" : "当前方式需要授权") + "；这里是候选方式，尚未切换。");
            return;
        }
        if (AppPrefs.SHIZUKU.equals(selected)) {
            status.setText(ShizukuShell.readiness());
            status.setTextColor(ShizukuShell.authorizedUid() >= 0 ? success : warning);
            detail.setText(ShizukuShell.authorizedUid() >= 0
                    ? "使用独立 Binder 服务；不需要 BerryFCM 持续连接无线调试。实际采样结果以监控页为准。"
                    : "需先启动 Shizuku 并完成一次应用授权；原配对身份保留。");
        } else if (AppPrefs.WIRELESS.equals(selected)) {
            if (PrivKitShell.isConnected()) {
                status.setText("授权成功");
                status.setTextColor(success);
                detail.setText("服务正在运行，可以使用 FCM 监控。");
            } else if (AdbPairingService.isPairingActive()) {
                status.setText("正在配对");
                detail.setText(prefs.pairingStatus());
            } else if (PrivKitShell.isStarting()) {
                status.setText("正在启动服务…");
                detail.setText("正在连接无线调试。");
            } else if (prefs.pairingRejected()) {
                status.setText("授权已失效");
                status.setTextColor(danger);
                detail.setText("系统已拒绝原有配对，请重新配对。");
            } else if (prefs.adbPaired()) {
                boolean failed = !PrivKitShell.lastStartupError(this).isEmpty();
                status.setText(failed ? "服务启动失败" : "服务未启动");
                detail.setText(failed ? "请重试启动服务，具体原因见下方“查看失败原因”。" : "已有配对记录，请先启动服务。");
            } else {
                boolean failed = prefs.pairingStatus().startsWith("配对失败");
                status.setText(failed ? "配对失败" : "需要 ADB 授权");
                detail.setText(failed ? "请重新打开系统配对码页面并输入六位码。具体原因见下方。" : "首次使用请完成无线配对。若已经配对，可检查已有授权。");
            }
        } else if (PrivilegeAccess.readyForUserAction(this)) {
            status.setText("Shell 上次验证成功");
            status.setTextColor(success);
            detail.setText(mode + " · " + prefs.shellIdentity()
                    + "\n每次实际命令都会验证 Shell 身份；不代表当前存在持续连接。");
        } else if (!prefs.shellError().isEmpty() && mode.equals(prefs.shellMode())) {
            status.setText("授权连接失败");
            status.setTextColor(danger);
            detail.setText(prefs.shellError() + "\n" + prefs.pairingStatus());
        } else {
            status.setText("未验证当前 Shell");
            detail.setText(AppPrefs.WIRELESS.equals(selected)
                    ? prefs.pairingStatus() + "\n先完成通知栏配对，配对成功后自动验证 Shell。"
                    : "选择当前方式后执行授权或连接验证。");
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
        if (!automaticCheckDone && !checking && !AdbPairingService.isPairingActive()
                && AppPrefs.WIRELESS.equals(selected) && !PrivKitShell.isConnected()
                && prefs.adbPaired() && !prefs.pairingRejected() && PrivKitShell.wirelessDebuggingState(this) == 1) {
            automaticCheckDone = true;
            checkShell();
            return;
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
        permissionWorker.shutdownNow();
        super.onDestroy();
    }
}
