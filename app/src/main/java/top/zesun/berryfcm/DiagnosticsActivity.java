package top.zesun.berryfcm;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DiagnosticsActivity extends Ui {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView results;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("诊断与日志", "连接证据 · 网络可达性 · 权限错误");
        LinearLayout status = card("原生诊断", "Google FCM Diagnostics 可用于对照短暂断连；页面是否导出取决于 GMS 版本。");
        button(status, "打开 Google 原生 FCM Diagnostics", () -> openGoogle(this), true);
        button(status, "查看 BerryFCM FCM 监控详情", () -> go(MonitorActivity.class), false);

        LinearLayout network = card("网络检查", "TCP 握手不等于 GMS 已认证，也不能证明无代理：测试遵循当前设备路由。");
        button(network, "检查 mtalk.google.com TCP 5228 / 5229 / 5230", this::checkNetwork, true);
        button(network, "检查 GMS Doze / 后台网络 / 通知权限", this::checkPolicies, false);
        results = text("尚未运行诊断", 13, false, muted);
        results.setTextIsSelectable(true);
        results.setPadding(0, dp(8), 0, 0);
        network.addView(results);
        button(network, "分享最近监控事件 / 错误", this::shareEvents, false);
    }

    static void openGoogle(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics");
            activity.startActivity(intent);
        } catch (RuntimeException error) {
            android.widget.Toast.makeText(activity,
                    "当前 GMS 未开放 Diagnostics 页面", android.widget.Toast.LENGTH_LONG).show();
        }
    }
    private void checkNetwork() {
        results.setText("正在检查网络，单项最长约 3 秒…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String command = "for p in 5228 5229 5230; do "
                        + "if /system/bin/toybox nc -4 -z -w 3 mtalk.google.com \"$p\""
                        + " </dev/null >/dev/null 2>&1; then echo \"$p|可建立 TCP\"; "
                        + "else echo \"$p|连接失败或超时\"; fi; done";
                String response = shell.execute(command);
                runOnUiThread(() -> results.setText(response
                        + "\n注意：Shell 路由、VPN 与 GMS UID 路由可能不相同。"));
            } catch (Exception error) {
                runOnUiThread(() -> results.setText("诊断失败：" + error.getMessage()));
            }
        });
    }
    private void checkPolicies() {
        results.setText("正在回读 GMS 系统策略…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String command = "echo '== Doze ==' ; cmd deviceidle whitelist 2>&1 | "
                        + "grep -F com.google.android.gms || true; "
                        + "echo '== DataSaver ==' ; cmd netpolicy get restrict-background 2>&1; "
                        + "echo '== Background Network ==' ; "
                        + "cmd netpolicy list restrict-background-whitelist 2>&1; "
                        + "echo '== GMS Notifications ==' ; "
                        + "cmd appops get com.google.android.gms POST_NOTIFICATION 2>&1";
                String output = shell.execute(command);
                runOnUiThread(() -> results.setText(output));
            } catch (Exception error) {
                runOnUiThread(() -> results.setText("诊断失败：" + error.getMessage()));
            }
        });
    }

    private void shareEvents() {
        String report = "BerryFCM 状态诊断\n"
                + "监控错误：" + FcmMonitorService.lastError(this) + "\n"
                + FcmMonitorService.lastSnapshot(this).toString() + "\n"
                + FcmMonitorService.lastEvents(this);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(share, "分享 BerryFCM 诊断"));
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
