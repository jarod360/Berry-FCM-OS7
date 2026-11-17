package top.zesun.berryfcm;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.InetSocketAddress;
import java.net.Socket;
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

        LinearLayout network = card("网络检查", "基础 TCP 测试无需 Root / ADB，使用 BerryFCM 自身网络路由；不代表 Google Play 服务已认证或能收到推送。");
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
        results.setText("正在测试 mtalk.google.com；每个端口最多等待 3 秒（DNS 解析另计）…");
        worker.execute(() -> {
            StringBuilder report = new StringBuilder();
            for (int port : new int[]{5228, 5229, 5230}) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("mtalk.google.com", port), 3000);
                    report.append(port).append("：TCP 握手成功\n");
                } catch (Exception error) {
                    report.append(port).append("：连接失败（")
                            .append(error.getClass().getSimpleName()).append("）\n");
                }
            }
            report.append("\n使用 BerryFCM 应用的网络路径；不检测 GMS UID 实际连接，不能证明 FCM 消息送达，也不能判断是否经过代理。");
            String output = report.toString();
            runOnUiThread(() -> results.setText(output));
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
