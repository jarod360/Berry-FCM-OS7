package top.zesun.berryfcm;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
    private String selected;
    private boolean checking;
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
        title("连接授权", "选择一种连接方式，按照步骤操作");

        LinearLayout card = card("当前授权状态", "只有真正运行 Shell 并返回身份后才算验证成功。");
        status = text("检查状态中", 20, true, ink);
        card.addView(status);
        detail = text("", 12, false, muted);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, dp(5), 0, dp(1));
        card.addView(detail);

        LinearLayout chooser = card("选择连接方式", "已 Root 推荐 Root；已启动 Shizuku 可选独立服务，无需保持无线 ADB 连接。");
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(tabs);
        chooser.addView(scroll);

        LinearLayout steps = card("按顺序操作", null);
        instructions = new LinearLayout(this);
        instructions.setOrientation(LinearLayout.VERTICAL);
        steps.addView(instructions);
        renderTabs();
        renderInstructions();
        renderStatus();
    }

    private void renderTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < MODES.length; i++) {
            final String mode = MODES[i];
            boolean active = mode.equals(selected);
            TextView tab = text(LABELS[i], 13, active, active ? 0xffffffff : ink);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? 0xff365ed6 : (surface == 0xfff4f6fa ? 0xffebeff8 : 0xff293650));
            bg.setCornerRadius(dp(12));
            tab.setBackground(bg);
            tab.setFocusable(true);
            tab.setContentDescription(LABELS[i] + (active ? "，当前选中" : "，点击切换"));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(42));
            params.rightMargin = dp(7);
            tabs.addView(tab, params);
            tab.setOnClickListener(v -> {
                if (checking) return;
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
        if (prefs.adbPaired()) {
            step("已保存配对身份", "配对身份保留。后台按需连接、执行后断开；执行期间系统可能短暂显示无线调试通知。");
            button(instructions, AppPrefs.WIRELESS.equals(prefs.mode()) ? "启动 / 立即检查 FCM 监控" : "使用已配对的无线连接", () -> {
                if (!AppPrefs.WIRELESS.equals(prefs.mode())) {
                    checkShell();
                    return;
                }
                prefs.monitorEnabled(true);
                FcmMonitorService.start(this);
                FcmMonitorService.checkNow(this);
                go(MonitorActivity.class);
            }, true);
            LinearLayout repair = disclosure(instructions, "重新配对 / 无线调试设置", false);
            wirelessSteps(repair);
        } else {
            wirelessSteps(instructions);
        }
    }

    private void wirelessSteps(LinearLayout parent) {
        stepIn(parent, "1 · 开始并打开无线调试",
                "点击后启动配对通知并跳到系统无线调试；打开“使用配对码配对设备”。");
        button(parent, "开始配对并打开无线调试", () -> beginPair(0), true);
        stepIn(parent, "2 · 在通知栏输入六位码",
                "停留在系统配对码页面，下拉通知栏输入六位码。BerryFCM 会自动搜索配对端口。");
        stepIn(parent, "3 · 自动运行",
                "配对成功后自动连接并开启 FCM 采样，不必返回再点授权或连接。");
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
        TextView tip = text("自动发现端口失败时，可直接在通知栏输入“端口:六位码”，例如 37123:123456。",
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 121);
            return;
        }
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            error("请先开启 BerryFCM 通知权限，才能从通知栏输入配对码");
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        try {
            AdbPairingService.begin(this, manualPort);
            status.setText("正在等待配对码");
            detail.setText("系统无线调试页面打开后，选择使用配对码配对设备，并下拉通知栏输入。");
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
        if (requestCode != 120 && requestCode != 121) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            pendingPairPort = -1;
            error("配对通知所需权限未授予，无法自动继续");
            return;
        }
        if (pendingPairPort >= 0) beginPair(pendingPairPort);
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
        if (checking || AppPrefs.EXTERNAL.equals(selected)) return;
        final String candidate = selected;
        checking = true;
        status.setText("正在检查候选连接…");
        detail.setText("验证成功后才切换后台连接方式；失败保留原设置。");
        worker.execute(() -> {
            try {
                String identity = PrivilegeAccess.probe(this, candidate, host, port);
                prefs.activateMode(candidate, host, port, identity);
                if (AppPrefs.WIRELESS.equals(candidate) || AppPrefs.SHIZUKU.equals(candidate)) prefs.monitorEnabled(true);
                runOnUiThread(() -> {
                    checking = false;
                    FcmMonitorService.resumeIfNeeded(this);
                    renderStatus();
                });
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "连接失败" : ex.getMessage();
                runOnUiThread(() -> {
                    checking = false;
                    status.setText("候选连接未通过");
                    detail.setText(message + "\n后台仍使用原有连接方式。");
                });
            }
        });
    }

    private void renderStatus() {
        if (status == null) return;
        if (AppPrefs.EXTERNAL.equals(selected)) {
            status.setText("电脑 USB · 初始化指引");
            detail.setText("这不是 APK 的授权模式。电脑启用 ADB TCP 后，回到 ADB TCP 标签连接验证。");
            return;
        }
        String mode = prefs.mode();
        if (!selected.equals(mode)) {
            status.setText("当前使用：" + (AppPrefs.ROOT.equals(mode) ? "Root" : AppPrefs.SHIZUKU.equals(mode) ? "Shizuku" : AppPrefs.STATIC.equals(mode) ? "ADB TCP" : "无线 ADB"));
            detail.setText("这里是候选连接的设置，尚未切换后台连接方式。");
            return;
        }
        if (AppPrefs.SHIZUKU.equals(selected)) {
            status.setText(ShizukuShell.readiness());
            detail.setText(ShizukuShell.authorizedUid() >= 0
                    ? "使用独立 Binder 服务；不需要 BerryFCM 持续连接无线调试。实际采样结果以监控页为准。"
                    : "需先启动 Shizuku 并完成一次应用授权；原配对身份保留。");
        } else if (AppPrefs.WIRELESS.equals(selected) && prefs.adbPaired()) {
            status.setText("配对已完成");
            String monitorError = FcmMonitorService.lastError(this);
            detail.setText(prefs.monitorEnabled()
                    ? monitorError.isEmpty()
                        ? "后台监控已开启，ADB 按需连接并在命令结束后断开。具体采样结果请到监控页查看。"
                        : "最近采样异常：" + monitorError + "\n可到监控页检查，无需重新配对。"
                    : "配对身份仍保留，监控尚未启用。点击下方按钮启动即可。");
        } else if (prefs.shellRecentlyVerified()) {
            status.setText("Shell 已验证");
            detail.setText(mode + " · " + prefs.shellIdentity()
                    + "\n此结果只证明近期 Shell 可用，GMS 原生状态读取权限仍须单独验证。");
        } else if (!prefs.shellError().isEmpty() && mode.equals(prefs.shellMode())) {
            status.setText("授权连接失败");
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
        if (!checking) {
            renderStatus();
            if (instructions != null && (AppPrefs.WIRELESS.equals(selected) || AppPrefs.SHIZUKU.equals(selected)))
                renderInstructions();
        }
    }
    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        worker.shutdownNow();
        super.onDestroy();
    }
}
