package top.zesun.berryfcm;

import android.Manifest;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AppPrefs prefs;
    private RadioGroup modes;
    private EditText host, pairPort, pairCode, staticPort;
    private CheckBox autoRestore;
    private LinearLayout serviceList;
    private TextView status;
    private final List<AccessibilityOps.Entry> services = new ArrayList<>();
    private final List<CheckBox> serviceChecks = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new AppPrefs(this);
        requestRuntimePermissions();
        buildUi();
        loadServices();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(28));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        scroll.addView(root);

        TextView title = text("BerryFCM Guardian", 24, true);
        root.addView(title);
        root.addView(text("无障碍服务管理 · Root / 无线 ADB / 静态端口 / USB 外部授权", 13, false));
        root.addView(gap(14));

        LinearLayout modeCard = card(root, "授权方式");
        modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        addMode("Root", AppPrefs.ROOT, modeCard);
        addMode("无线 ADB", AppPrefs.WIRELESS, modeCard);
        addMode("静态端口", AppPrefs.STATIC, modeCard);
        addMode("USB/外部", AppPrefs.EXTERNAL, modeCard);
        addMode("手动", AppPrefs.MANUAL, modeCard);
        modeCard.addView(modes);
        modes.setOnCheckedChangeListener((group, checkedId) -> saveMode());
        String savedMode = prefs.mode();
        for (int i = 0; i < modes.getChildCount(); i++) {
            RadioButton b = (RadioButton) modes.getChildAt(i);
            if (savedMode.equals(b.getTag())) b.setChecked(true);
        }
        if (modes.getCheckedRadioButtonId() == -1) ((RadioButton) modes.getChildAt(0)).setChecked(true);

        LinearLayout connection = card(root, "连接配置");
        host = edit(connection, "设备地址（本机静态端口可用 127.0.0.1）", prefs.host());
        pairPort = edit(connection, "无线调试配对端口", String.valueOf(prefs.pairPort()));
        pairCode = edit(connection, "无线调试配对码", "");
        pairCode.setInputType(2);
        staticPort = edit(connection, "静态 ADB 端口", String.valueOf(prefs.staticPort()));
        Button pair = button("配对并连接无线 ADB", false);
        pair.setOnClickListener(v -> pairWireless());
        connection.addView(pair);
        Button connect = button("连接当前授权方式并检测", true);
        connect.setOnClickListener(v -> connectCurrentMode());
        connection.addView(connect);
        connection.addView(text("无线 ADB 首次需要在系统开发者选项中开启无线调试，并输入配对屏幕上的 IP、配对端口和配对码。配对成功后，系统保存配对关系；重启后仍需无线调试处于开启状态，APK 才能重新连接。", 12, false));

        LinearLayout servicesCard = card(root, "可管理的无障碍服务");
        servicesCard.addView(text("勾选需要由本工具维护的服务。应用时只替换本工具上次管理的条目，保留其它无障碍服务。", 12, false));
        serviceList = new LinearLayout(this);
        serviceList.setOrientation(LinearLayout.VERTICAL);
        servicesCard.addView(serviceList);
        Button refresh = button("重新扫描无障碍服务", false);
        refresh.setOnClickListener(v -> loadServices());
        servicesCard.addView(refresh);
        Button apply = button("应用并保存所选服务", true);
        apply.setOnClickListener(v -> applySelected());
        servicesCard.addView(apply);
        Button openSettings = button("打开系统无障碍设置", false);
        openSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        servicesCard.addView(openSettings);

        LinearLayout autoCard = card(root, "开机自动恢复");
        autoRestore = new CheckBox(this);
        autoRestore.setText("开机后自动连接当前授权方式，并恢复上面已选服务");
        autoRestore.setTextColor(Color.rgb(31, 45, 66));
        autoRestore.setChecked(prefs.autoRestore());
        autoCard.addView(autoRestore);
        autoCard.addView(text("Root 模式需要 Root 授权仍有效；无线 ADB 模式要求无线调试保持开启且此前已配对；静态端口要求 adbd 端口在重启后仍可访问。Android/OriginOS 可能限制开机启动，不能保证每台设备都允许后台自动启动。", 12, false));
        Button saveAuto = button("保存开机恢复设置", true);
        saveAuto.setOnClickListener(v -> { prefs.autoRestore(autoRestore.isChecked()); saveMode(); show("已保存开机恢复设置"); });
        autoCard.addView(saveAuto);
        Button tile = button("添加下拉快捷设置磁贴", false);
        tile.setOnClickListener(v -> requestTile());
        autoCard.addView(tile);

        LinearLayout usb = card(root, "USB / 外部 ADB 说明");
        usb.addView(text("手机 APK 不能把自己当成电脑端 USB ADB 主机，也不能在没有用户授权时自行打开 USB 调试。可先用电脑 USB 连接手机，在电脑执行 adb tcpip 5555，再回到本应用选择“静态端口”，地址填写 127.0.0.1、端口填写 5555。该 TCP 端口通常只在当前启动周期有效，重启后可能需要重新执行。\n\n也可以选择“手动”，由系统设置页面启用/关闭无障碍服务。USB/外部模式只提供初始化说明，不伪装成 APK 内部已建立的 ADB 会话。", 12, false));
        Button dev = button("打开开发者选项", false);
        dev.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        usb.addView(dev);

        LinearLayout statusCard = card(root, "当前状态");
        status = text("尚未检测授权", 13, false);
        status.setTextIsSelectable(true);
        statusCard.addView(status);
        Button request = button("请求添加本应用快捷磁贴", false);
        request.setOnClickListener(v -> requestTile());
        statusCard.addView(request);

        setContentView(scroll);
    }

    private void addMode(String label, String value, LinearLayout parent) {
        RadioButton b = new RadioButton(this);
        b.setText(label);
        b.setTag(value);
        b.setTextSize(12);
        b.setTextColor(Color.rgb(31, 45, 66));
        modes.addView(b, new RadioGroup.LayoutParams(0, dp(44), 1));
    }

    private LinearLayout card(LinearLayout root, String heading) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        root.addView(box, lp);
        TextView h = text(heading, 16, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(8);
        box.addView(h, hp);
        return box;
    }

    private EditText edit(LinearLayout parent, String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(Color.rgb(25, 39, 60));
        e.setHint(hint);
        e.setText(value);
        e.setPadding(dp(10), dp(7), dp(10), dp(7));
        parent.addView(e, new LinearLayout.LayoutParams(-1, dp(48)));
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : Color.rgb(35, 50, 75));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? Color.rgb(61, 103, 216) : Color.rgb(238, 242, 249)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.topMargin = dp(7);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(34, 48, 70));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private View gap(int height) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return v; }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void saveMode() {
        if (modes == null) return;
        int id = modes.getCheckedRadioButtonId();
        if (id != -1) {
            String mode = String.valueOf(((RadioButton) modes.findViewById(id)).getTag());
            prefs.mode(mode);
            if ((AppPrefs.MANUAL.equals(mode) || AppPrefs.EXTERNAL.equals(mode)) && autoRestore != null) {
                autoRestore.setChecked(false);
                prefs.autoRestore(false);
            }
        }
        if (host != null) prefs.host(host.getText().toString().trim());
        if (pairPort != null) prefs.pairPort(parseInt(pairPort.getText().toString(), 37123));
        if (staticPort != null) prefs.staticPort(parseInt(staticPort.getText().toString(), 5555));
    }

    private int parseInt(String s, int fallback) { try { int n = Integer.parseInt(s.trim()); return n > 0 && n < 65536 ? n : fallback; } catch (Exception e) { return fallback; } }

    private String currentMode() {
        int id = modes.getCheckedRadioButtonId();
        return id == -1 ? AppPrefs.ROOT : String.valueOf(((RadioButton) modes.findViewById(id)).getTag());
    }

    private void loadServices() {
        services.clear(); services.addAll(AccessibilityOps.discover(this));
        serviceChecks.clear(); serviceList.removeAllViews();
        Set<String> selected = prefs.selected();
        if (services.isEmpty()) { serviceList.addView(text("未发现声明无障碍服务的应用。", 13, false)); return; }
        for (AccessibilityOps.Entry entry : services) {
            CheckBox check = new CheckBox(this);
            check.setText(entry.label + "\n" + entry.component);
            check.setTextSize(12);
            check.setTextColor(Color.rgb(40, 55, 78));
            check.setChecked(selected.contains(entry.component));
            serviceList.addView(check);
            serviceChecks.add(check);
        }
        show("已发现 " + services.size() + " 个无障碍服务声明");
    }

    private Set<String> checkedComponents() {
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < serviceChecks.size(); i++) if (serviceChecks.get(i).isChecked()) selected.add(services.get(i).component);
        return selected;
    }

    private PrivilegeShell openShell(String mode) throws Exception {
        if (AppPrefs.ROOT.equals(mode)) return new RootShell();
        if (AppPrefs.WIRELESS.equals(mode)) {
            if (!AdbConnectionManager.get(this).autoConnectLocal(this)) throw new IllegalStateException("无线 ADB 未连接；请先配对并确认无线调试开启");
            return new AdbShell(this);
        }
        if (AppPrefs.STATIC.equals(mode)) {
            if (!AdbConnectionManager.get(this).connectStatic(host.getText().toString().trim(), parseInt(staticPort.getText().toString(), 5555)))
                throw new IllegalStateException("静态端口连接失败；确认 adbd 正在监听该端口");
            return new AdbShell(this);
        }
        throw new IllegalStateException("此模式不能直接建立本机 Shell 会话");
    }

    private void pairWireless() {
        saveMode();
        String h = host.getText().toString().trim();
        int port = parseInt(pairPort.getText().toString(), 37123);
        String code = pairCode.getText().toString().trim();
        if (h.isEmpty() || code.isEmpty()) { show("请填写设备 IP、配对端口和配对码"); return; }
        runAsync(() -> {
            AdbConnectionManager adb = AdbConnectionManager.get(this);
            boolean ok = adb.pairAndConnect(this, h, port, code);
            if (!ok) throw new IllegalStateException("配对或后续连接未成功");
            return adb.shell("id");
        }, result -> { prefs.mode(AppPrefs.WIRELESS); show("无线 ADB 已连接\n" + result); });
        pairCode.setText("");
    }

    private void connectCurrentMode() {
        saveMode();
        String mode = currentMode();
        if (AppPrefs.MANUAL.equals(mode)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (AppPrefs.EXTERNAL.equals(mode)) {
            show("USB/外部模式需要先通过电脑 ADB 初始化。请阅读下方 USB / 外部 ADB 说明。");
            return;
        }
        runAsync(() -> {
            try (PrivilegeShell shell = openShell(mode)) { return shell.execute("id; settings get secure accessibility_enabled"); }
        }, result -> show("授权通道可用\n" + result));
    }

    private void applySelected() {
        saveMode();
        Set<String> selected = checkedComponents();
        prefs.selected(selected);
        String mode = currentMode();
        if (AppPrefs.MANUAL.equals(mode) || AppPrefs.EXTERNAL.equals(mode)) {
            show("手动/USB 外部模式不提供 APK 内部 Shell。请先切换 Root、无线 ADB 或静态端口完成授权。");
            return;
        }
        runAsync(() -> {
            try (PrivilegeShell shell = openShell(mode)) {
                Set<String> previous = prefs.managed();
                String output = shell.execute(AccessibilityOps.applyCommand(previous, selected));
                String verify = shell.execute(AccessibilityOps.readEnabledCommand());
                int enabled = 0;
                for (String component : selected) if (verify.contains(component)) enabled++;
                for (String component : previous) if (!selected.contains(component) && verify.contains(component))
                    throw new IllegalStateException("系统仍返回已取消的服务：" + component);
                if (enabled != selected.size()) throw new IllegalStateException("系统未确认全部目标服务，实际 " + enabled + "/" + selected.size());
                prefs.managed(selected);
                return "已提交并复查：" + enabled + "/" + selected.size() + " 个目标服务可见\n" + output.trim() + "\n" + verify.trim();
            }
        }, result -> show(result));
    }

    private interface Work { String run() throws Exception; }
    private interface Done { void accept(String result); }
    private void runAsync(Work work, Done done) {
        show("正在执行…");
        worker.execute(() -> {
            try { String result = work.run(); runOnUiThread(() -> done.accept(result)); }
            catch (Throwable e) { runOnUiThread(() -> show("操作失败：" + e.getMessage())); }
        });
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT < 33) { show("系统版本低于 Android 13，不支持应用内请求添加快捷设置磁贴；可在系统磁贴编辑页手动添加。"); return; }
        StatusBarManager manager = getSystemService(StatusBarManager.class);
        if (manager == null) { show("系统不支持快捷设置磁贴请求"); return; }
        manager.requestAddTileService(new ComponentName(this, RestoreTileService.class),
                "BerryFCM 恢复", Icon.createWithResource(this, android.R.drawable.ic_popup_sync),
                getMainExecutor(), result -> show("系统磁贴请求结果：" + result));
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 1001);
        }
    }

    private void show(String message) {
        if (status != null) status.setText(message);
        Toast.makeText(this, message.length() > 70 ? message.substring(0, 70) + "…" : message, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onPause() { saveMode(); if (autoRestore != null) prefs.autoRestore(autoRestore.isChecked()); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
