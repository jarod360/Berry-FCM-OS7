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

public final class AuthActivity extends Ui {
    private static final String[] MODES =
            {AppPrefs.WIRELESS, AppPrefs.ROOT, AppPrefs.STATIC, AppPrefs.EXTERNAL};
    private static final String[] LABELS = {"无线 ADB", "Root", "ADB TCP", "电脑 USB"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout tabs, instructions;
    private TextView status, detail;
    private String selected;
    private boolean checking;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selected = prefs.mode();
        title("授权中心", "选择授权方式，完成连接后验证 Shell");

        LinearLayout card = card("当前连接", "是否可用以最近一次实际 Shell 验证为准。");
        status = text("检查状态中", 20, true, ink);
        card.addView(status);
        detail = text("", 12, false, muted);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, dp(5), 0, dp(1));
        card.addView(detail);

        LinearLayout chooser = card("授权方式", "横向切换；仅显示选中方式的操作流程。");
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(tabs);
        chooser.addView(scroll);

        LinearLayout steps = card("操作步骤", null);
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
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(42));
            params.rightMargin = dp(7);
            tabs.addView(tab, params);
            tab.setOnClickListener(v -> {
                selected = mode;
                // USB is documentation, not a usable Shell transport.
                if (!AppPrefs.EXTERNAL.equals(mode)) prefs.mode(mode);
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
        step("1 · 开始配对通知", "启动监听，尝试自动发现本机 ADB 配对端口。");
        button(instructions, "开始通知栏配对", () -> beginPair(0), true);
        step("2 · 在系统中生成配对码",
                "打开开发者选项 → 无线调试 → 使用配对码配对设备，保持此页面打开。");
        button(instructions, "打开开发者选项 / 无线调试", this::openDevelopers, false);
        step("3 · 从通知栏完成配对",
                "在系统无线调试界面下拉通知栏，向 BerryFCM 通知提交六位配对码，无需切回应用。");
        if (prefs.pairingStatus().startsWith("配对成功")
                || (AppPrefs.WIRELESS.equals(prefs.shellMode()) && prefs.shellVerifiedAt() > 0)) {
            button(instructions, "重新连接 / 验证 ADB Shell", this::checkShell, false);
        }

        step("备用方式", "如果自动发现失败，在通知中输入“端口:六位码”。");
        TextView fallbackToggle = text("手动输入配对端口  ›", 13, true, accent);
        fallbackToggle.setPadding(0, dp(9), 0, dp(7));
        instructions.addView(fallbackToggle);
        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setVisibility(View.GONE);
        instructions.addView(fallback);
        EditText pairingPort = field(fallback, "系统配对页面显示的端口", "", true);
        button(fallback, "按指定端口开始通知栏配对", () -> {
            int port = parsePort(pairingPort.getText().toString());
            if (port == 0) { error("请输入有效配对端口"); return; }
            beginPair(port);
        }, false);
        fallbackToggle.setOnClickListener(v -> fallback.setVisibility(
                fallback.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        button(instructions, "取消配对通知", () -> AdbPairingService.stop(this), false);
    }

    private void root() {
        step("通过 KernelSU / Magisk 授权",
                "系统 Root 管理器可能弹出授权请求。只有 Shell 执行 id 返回 uid=0 才算完成。");
        button(instructions, "申请并验证 Root Shell", this::checkShell, true);
    }

    private void tcp() {
        step("连接已有 ADB TCP 端口",
                "需已开启 adbd TCP 服务；仅填写地址不会启用系统无线调试。");
        EditText host = field(instructions, "ADB 主机地址", prefs.host(), false);
        EditText port = field(instructions, "ADB 端口", String.valueOf(prefs.staticPort()), true);
        button(instructions, "保存并验证 ADB Shell", () -> {
            String address = host.getText().toString().trim();
            int number = parsePort(port.getText().toString());
            if (address.isEmpty() || number == 0) { error("主机地址或端口无效"); return; }
            prefs.mode(AppPrefs.STATIC);
            prefs.host(address);
            prefs.staticPort(number);
            checkShell();
        }, true);
    }

    private void usb() {
        step("在电脑上初始化 ADB TCP", "USB 连接必须先经系统授权。手机 APK 本身不能授予持续 Shell 权限。");
        step("电脑执行", "adb tcpip 5555");
        step("返回 APP", "切到 ADB TCP，连接 127.0.0.1:5555 并验证。重启后端口可能关闭。");
        button(instructions, "切换到 ADB TCP", () -> {
            selected = AppPrefs.STATIC;
            prefs.mode(selected);
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
        if (Build.VERSION.SDK_INT < 30) { error("无线配对需要 Android 11 或更新版本"); return; }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 120);
            error("请先允许通知权限，再次点击开始配对");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 121);
            error("请先允许附近设备权限，再次点击开始配对");
            return;
        }
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            error("通知权限已关闭；请在系统设置中开启以便从通知栏输入配对码");
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        try {
            AdbPairingService.begin(this, manualPort);
            status.setText("等待系统配对码");
            detail.setText("先在无线调试页面生成配对码，再下拉系统通知栏提交。");
        } catch (RuntimeException e) {
            status.setText("无法开始配对");
            detail.setText(String.valueOf(e.getMessage()));
        }
    }

    private void openDevelopers() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
        catch (RuntimeException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void checkShell() {
        if (checking || AppPrefs.EXTERNAL.equals(selected)) return;
        checking = true;
        status.setText("正在验证 Shell…");
        detail.setText("正在执行 id 并检查实际身份。");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String id = prefs.shellIdentity();
                runOnUiThread(() -> {
                    checking = false;
                    renderStatus();
                });
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "连接失败" : ex.getMessage();
                prefs.shellFailed(message);
                runOnUiThread(() -> {
                    checking = false;
                    renderStatus();
                });
            }
        });
    }

    private void renderStatus() {
        if (status == null) return;
        String mode = prefs.mode();
        if (prefs.shellRecentlyVerified()) {
            status.setText("Shell 已验证");
            detail.setText(mode + " · " + prefs.shellIdentity()
                    + "\n此结果只证明近期 Shell 可用，GMS Socket 权限仍须单独验证。");
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
        if (!checking) renderStatus();
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
