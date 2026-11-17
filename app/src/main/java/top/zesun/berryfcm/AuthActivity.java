package top.zesun.berryfcm;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class AuthActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText host, staticPort, pairingPort;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("授权中心", "ADB / Root · 连接状态与实际权限验证");
        LinearLayout mode = card("授权方式", "推荐无线 ADB。已保存配对身份并不代表当前 Shell 可用。");
        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        for (String[] option : new String[][]{
                {AppPrefs.WIRELESS, "无线 ADB（推荐）"},
                {AppPrefs.ROOT, "Root（需 KernelSU / Magisk 授权）"},
                {AppPrefs.STATIC, "静态 ADB TCP 端口"},
                {AppPrefs.EXTERNAL, "电脑 USB ADB 授权说明"}}) {
            RadioButton b = new RadioButton(this);
            b.setText(option[1]);
            b.setTextColor(ink);
            b.setTag(option[0]);
            choices.addView(b);
            if (prefs.mode().equals(option[0])) b.setChecked(true);
        }
        choices.setOnCheckedChangeListener((group, id) -> {
            RadioButton selected = group.findViewById(id);
            if (selected != null) prefs.mode(String.valueOf(selected.getTag()));
        });
        mode.addView(choices);
        status = text(prefs.pairingStatus(), 13, false, muted);
        status.setTextIsSelectable(true);
        mode.addView(status);
        button(mode, "验证当前 Root / ADB Shell 能力", this::checkShell, true);

        LinearLayout wireless = card("无线 ADB 通知栏配对",
                "① 开始监听 → ② 在系统“无线调试”点“使用配对码配对设备” → ③ 下拉通知栏输入 6 位码。无需切回 APP 输入。");
        button(wireless, "1. 开始通知栏配对（自动发现端口）", () -> beginPair(0), true);
        button(wireless, "2. 打开开发者选项 / 无线调试", this::openDevelopers, false);
        pairingPort = field(wireless, "mDNS 无法发现时，输入页面显示的配对端口", "");
        pairingPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        button(wireless, "备用：指定端口后在通知栏输入配对码", () -> {
            int port = parsePort(pairingPort.getText().toString(), 0);
            if (port == 0) { error("请填写无线调试“配对设备”界面中的配对端口"); return; }
            beginPair(port);
        }, false);
        button(wireless, "取消配对通知", () -> AdbPairingService.stop(this), false);

        LinearLayout tcp = card("静态 ADB TCP", "只有 adbd 已经监听 TCP 端口时才能连接。");
        host = field(tcp, "设备地址", prefs.host());
        staticPort = field(tcp, "ADB 连接端口", String.valueOf(prefs.staticPort()));
        staticPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        button(tcp, "保存静态连接配置", () -> {
            prefs.host(host.getText().toString().trim());
            prefs.staticPort(parsePort(staticPort.getText().toString(), 5555));
            prefs.mode(AppPrefs.STATIC);
            error("已保存，点击上方验证 Shell");
        }, false);

        LinearLayout guidance = card("电脑 USB ADB", "手机 APK 无法自行开启系统无线调试。电脑可通过 USB ADB 初始化静态调试端口；WRITE_SECURE_SETTINGS 不等于持续 Shell 权限。");
        line(guidance, "电脑执行：adb tcpip 5555", "然后用静态 ADB 连接 127.0.0.1:5555；系统或重启可能关闭该端口。", null);
    }

    private EditText field(LinearLayout box, String hint, String value) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextColor(ink);
        edit.setHintTextColor(muted);
        edit.setTextSize(14);
        edit.setHint(hint);
        edit.setText(value);
        box.addView(edit);
        return edit;
    }
    private static int parsePort(String s, int fallback) {
        try {
            int port = Integer.parseInt(s.trim());
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }
    private void beginPair(int manualPort) {
        if (Build.VERSION.SDK_INT < 30) { error("无线 ADB 配对需要 Android 11 或更新系统"); return; }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 120);
            error("请先允许通知权限，再点击开始通知栏配对");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 121);
            error("请先允许附近设备权限，再点击开始配对；此权限用于 mDNS 端口发现");
            return;
        }
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            error("通知已被系统关闭，请先开启 BerryFCM 通知，否则无法在通知栏输入配对码");
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        try {
            AdbPairingService.begin(this, manualPort);
            status.setText("配对通知已启动；切到无线调试页面生成配对码，下拉通知栏输入");
        } catch (RuntimeException error) { status.setText("无法启动配对服务：" + error.getMessage()); }
    }
    private void openDevelopers() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
        catch (RuntimeException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }
    private void checkShell() {
        status.setText("正在连接，检查 Shell UID…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String id = shell.execute("id").trim();
                String result = id + "\n权限：ADB Shell != Root。网络与 GMS Socket 权限以监控页实测为准。";
                runOnUiThread(() -> status.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("授权通道不可用：" + error.getMessage()));
            }
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if (status != null) status.setText(prefs.pairingStatus());
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
