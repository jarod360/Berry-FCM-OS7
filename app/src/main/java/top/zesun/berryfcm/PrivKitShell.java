package top.zesun.berryfcm;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import priv.kit.core.Privilege;
import priv.kit.core.PrivilegeConfig;
import priv.kit.core.PrivilegeServerInfo;
import priv.kit.core.command.PrivilegeCommand;
import priv.kit.core.command.PrivilegeCommandProcess;
import priv.kit.core.command.PrivilegeCommandResult;

/** ADB launches the official Priv Kit starter once; commands use Binder. */
final class PrivKitShell {
    private static final Privilege KIT = Privilege.INSTANCE;
    private static boolean autoAttempted;
    private static final long RECOVERY_COOLDOWN_MS = 300000L;
    private static final String START_PREFS = "berry_privkit_start";
    private static volatile String status = "独立服务尚未启动";

    private PrivKitShell() { }

    static void initialize() {
        // Same passive owner-reconnect window as GKD, but never have the
        // privileged server actively relaunch the application.
        PrivilegeConfig.INSTANCE.configure(3600000L, false);
        KIT.getServerState();
    }

    private static SharedPreferences startPrefs(Context context) {
        return context.getSharedPreferences(START_PREFS, Context.MODE_PRIVATE);
    }

    private static int bootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
        catch (RuntimeException ignored) { return -1; }
    }

    private static long cooldownRemaining(Context context) {
        SharedPreferences p = startPrefs(context);
        return LaunchCooldown.remaining(RECOVERY_COOLDOWN_MS,
                p.getLong("elapsed", -1), p.getLong("wall", -1), p.getInt("boot", -1),
                SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount(context));
    }

    private static void rememberLaunch(Context context) throws IOException {
        // Persist before opening ADB. A process crash must not erase throttling.
        SharedPreferences p = startPrefs(context);
        if (!p.edit()
                .putLong("attempt_count", p.getLong("attempt_count", 0) + 1)
                .putString("last_result", "requested")
                .putLong("elapsed", SystemClock.elapsedRealtime())
                .putLong("wall", System.currentTimeMillis())
                .putInt("boot", bootCount(context)).commit())
            throw new IOException("独立服务启动记录无法保存");
    }

    static boolean isConnected() {
        PrivilegeServerInfo info = KIT.getServerState().getValue();
        return info != null && info.getUid() == 2000 && info.getLifecycleBinder().isBinderAlive();
    }

    static String status() {
        if (isConnected()) return "独立服务运行中";
        return "独立服务运行中".equals(status) ? "独立服务已断开" : status;
    }

    static synchronized void allowStart(Context context) {
        autoAttempted = false;
        startPrefs(context).edit().remove("elapsed").remove("wall").remove("boot").apply();
    }

    static synchronized void start(Context context) throws Exception {
        start(context, true);
    }

    private static void start(Context context, boolean explicit) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("独立服务必须在后台启动");
        if (!new AppPrefs(context).adbPaired()) throw new IOException("请先完成无线 ADB 配对");
        if (isConnected() && KIT.pingServer()) {
            autoAttempted = false;
            return;
        }
        autoAttempted = true;
        status = "正在启动独立服务";
        boolean launched = false;
        try {
            long grace = SystemClock.elapsedRealtime() + 5000L;
            while (!isConnected() && SystemClock.elapsedRealtime() < grace)
                Thread.sleep(100L);
            if (!isConnected()) {
                PrivilegeServerInfo previous = KIT.getServerState().getValue();
                if (previous != null && KIT.pingServer())
                    throw new IOException("独立服务 UID 不匹配");
                if (!explicit) {
                    long remaining = cooldownRemaining(context);
                    if (remaining > 0) {
                        autoAttempted = false;
                        throw new IOException("独立服务等待恢复冷却约 " + ((remaining + 999) / 1000)
                                + " 秒；无需重新配对");
                    }
                }
                String launch = KIT.getNativeStarterCommand();
                rememberLaunch(context);
                launched = true;
                AdbConnectionManager.get(context).executeOnce(context,
                        AppPrefs.WIRELESS, "127.0.0.1", 0, launch);
                long deadline = SystemClock.elapsedRealtime() + 15000L;
                while (!isConnected() && SystemClock.elapsedRealtime() < deadline)
                    Thread.sleep(150L);
            }
            if (!isConnected() || !KIT.pingServer())
                throw new IOException("独立服务未回传有效的 UID 2000 Binder");
            if (launched) startPrefs(context).edit().putString("last_result", "binder_ready").apply();
            autoAttempted = false;
            status = "独立服务运行中";
        } catch (InterruptedException ex) {
            if (launched) startPrefs(context).edit().putString("last_result", "interrupted").apply();
            Thread.currentThread().interrupt();
            status = "独立服务启动已中断";
            throw ex;
        } catch (Exception ex) {
            if (launched) startPrefs(context).edit().putString("last_result", "failed").apply();
            status = "独立服务启动失败，请在授权设置中重新启动，无需重新配对";
            throw ex;
        }
    }

    static synchronized String execute(Context context, String command) throws Exception {
        if (!isConnected() || !KIT.pingServer()) {
            // No per-sample ADB fallback, even if the starter is incompatible.
            if (autoAttempted) throw new IOException(status());
            long remaining = cooldownRemaining(context);
            if (remaining > 0)
                throw new IOException("独立服务等待恢复冷却约 " + ((remaining + 999) / 1000)
                        + " 秒；也可在连接设置中启动");
            start(context, false);
        }
        autoAttempted = false;
        PrivilegeCommand request = new PrivilegeCommand(
                Arrays.asList("/system/bin/sh", "-c", "(" + command + ") 2>&1"),
                Collections.emptyMap(), null);
        PrivilegeCommandProcess process = BuildersKt.<PrivilegeCommandProcess>runBlocking(
                EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> KIT.startCommand(request, 45000L, continuation));
        try {
            PrivilegeCommandResult result = BuildersKt.<PrivilegeCommandResult>runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> process.awaitResult(200000, continuation));
            if (result.getStdoutTruncated() || result.getStderrTruncated())
                throw new IOException("独立 Shell 输出超限");
            return new String(result.getStdout(), StandardCharsets.UTF_8)
                    + new String(result.getStderr(), StandardCharsets.UTF_8);
        } finally {
            process.close();
        }
    }

    // Read-only: sharing diagnostics must neither open ADB nor start a server.
    static String diagnostic(Context context) {
        SharedPreferences p = startPrefs(context);
        PrivilegeServerInfo info = KIT.getServerState().getValue();
        boolean live = info != null && info.getLifecycleBinder().isBinderAlive();
        long stamp = p.getLong("wall", 0);
        return "权限模式：" + new AppPrefs(context).mode()
                + "\n配对身份已保存：" + new AppPrefs(context).adbPaired()
                + "\nBinder：" + (live ? "存活 · UID=" + info.getUid() + " · PID=" + info.getPid() : "未连接")
                + "\n本 APP 进程 PID：" + android.os.Process.myPid()
                + "\n累计 ADB 启动尝试：" + p.getLong("attempt_count", 0)
                + "\n最近启动结果：" + p.getString("last_result", "none")
                + "\n最近启动请求时间（epoch ms）：" + stamp
                + "\n自动启动冷却剩余（秒）：" + ((cooldownRemaining(context) + 999) / 1000)
                + "\n说明：启动尝试计数不是成功连接次数；日常 Binder 命令不增加此计数。";
    }

    static synchronized void stop() {
        try { if (KIT.pingServer()) KIT.shutdownServer(); }
        catch (RuntimeException ignored) { }
        autoAttempted = false;
        status = "独立服务已停止";
    }
}
