package top.zesun.berryfcm;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
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
import priv.kit.core.PrivilegeStartupException;
import priv.kit.core.adb.PrivilegeAdbManager;
import priv.kit.core.adb.PrivilegeAdbConnectionOptions;
import priv.kit.core.adb.PrivilegeAdbWirelessDebuggingControl;
import priv.kit.core.adb.PrivilegeAdbPairingResult;
import priv.kit.core.adb.PrivilegeAdbPairingCheckResult;
import priv.kit.core.command.PrivilegeCommand;
import priv.kit.core.command.PrivilegeCommandProcess;
import priv.kit.core.command.PrivilegeCommandResult;

/** ADB launches the official Priv Kit starter once; commands use Binder. */
final class PrivKitShell {
    private static final Privilege KIT = Privilege.INSTANCE;
    private static final String ADB_NAME = "BerryFCM";
    private static volatile String startupTrace = "";
    private static final String TRACE_KEY = "last_startup_trace";
    private static boolean autoAttempted;
    private static final long RECOVERY_COOLDOWN_MS = 300000L;
    private static final String START_PREFS = "berry_privkit_start";
    private static volatile String status = "服务未启动";
    private static volatile boolean starting;
    private static final java.util.concurrent.atomic.AtomicBoolean foregroundRecovery = new java.util.concurrent.atomic.AtomicBoolean();
    static boolean isStarting() { return starting; }

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

    private static PrivilegeAdbManager manager() throws PrivilegeStartupException { return KIT.createAdbManager(ADB_NAME); }

    // Upstream v0.20.0 persists its encrypted key in files/.priv-kit/adbkey.
    // Existence is ONLY a reason to try authentication, never evidence of pairing.
    static boolean hasStoredIdentity(Context context) {
        return new java.io.File(context.getApplicationContext().getFilesDir(), ".priv-kit/adbkey").isFile();
    }

    static boolean canRecoverExisting(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        return AppPrefs.WIRELESS.equals(prefs.mode()) && !prefs.pairingRejected()
                && !prefs.servicePaused(AppPrefs.WIRELESS)
                && (prefs.adbPaired() || hasStoredIdentity(context))
                && !AdbPairingService.isPairingActive()
                && localNetworkAllowed(context) && wirelessDebuggingState(context) != 0;
    }

    // A foreground entry may recover a saved identity even if old versions
    // never persisted the pairing flag. Share one bounded attempt across pages.
    static void recoverOnForeground(Context context) {
        Context app = context.getApplicationContext();
        if (isConnected() || isStarting() || !canRecoverExisting(app)
                || cooldownRemaining(app) > 0 || !foregroundRecovery.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                synchronized (PrivKitShell.class) {
                    if (isConnected() || !canRecoverExisting(app) || cooldownRemaining(app) > 0) return;
                    start(app, false);
                    String identity = execute(app, "id").trim();
                    if (!identity.matches("(?s)^uid=2000(?:\\D.*)?$"))
                        throw new IOException("独立服务身份不匹配");
                    AppPrefs prefs = new AppPrefs(app);
                    if (AppPrefs.WIRELESS.equals(prefs.mode())) {
                        prefs.activateMode(AppPrefs.WIRELESS, prefs.host(), prefs.staticPort(), identity);
                        prefs.enableMonitorOnFirstAuthorization();
                    }
                }
                FcmMonitorService.resumeIfNeeded(app);
            } catch (Exception failure) {
                new AppPrefs(app).shellFailed(errorChain(failure));
            } finally {
                foregroundRecovery.set(false);
            }
        }, "berry-adb-recovery").start();
    }

    static boolean localNetworkAllowed(Context context) {
        return Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(
                "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED;
    }

    static int systemConnectPort() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            int port = (Integer) properties.getMethod("getInt", String.class, int.class)
                    .invoke(null, "service.adb.tls.port", -1);
            return port > 0 && port <= 65535 ? port : 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    static int wirelessDebuggingState(Context context) {
        try {
            int state = Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", -1);
            // SDK 37 may redact this setting to zero; unknown is not disabled.
            return Build.VERSION.SDK_INT >= 37 && state == 0 ? -1 : state;
        }
        catch (RuntimeException ignored) { return -1; }
    }

    static int discoverPairingPort() throws Exception {
        PrivilegeAdbManager adb = manager();
        return BuildersKt.<Integer>runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> {
                    try { return adb.discoverPairingPort(6000L, continuation); }
                    catch (PrivilegeStartupException ex) { throw new IllegalStateException(ex); }
                });
    }

    static synchronized void pair(Context context, Integer port, String code) throws Exception {
        PrivilegeAdbManager adb = manager();
        startupTrace = "";
        startPrefs(context).edit().remove(TRACE_KEY).remove("last_error").apply();
        PrivilegeAdbPairingResult result = BuildersKt.<PrivilegeAdbPairingResult>runBlocking(
                EmptyCoroutineContext.INSTANCE, (scope, continuation) -> {
                    try { return adb.pair(code, port, 15000L, continuation); }
                    catch (PrivilegeStartupException ex) { throw new IllegalStateException(ex); }
                });
        if (result == null || result.getPort() <= 0 || (port != null && result.getPort() != port))
            throw new IOException("配对未返回有效结果");
        new AppPrefs(context).markPairingExchange();
        allowStart(context);
        // A new explicit pairing must not complete against a legacy Binder.
        if (isConnected()) {
            KIT.shutdownServer();
            long end = SystemClock.elapsedRealtime() + 5000L;
            while (isConnected() && SystemClock.elapsedRealtime() < end) Thread.sleep(100L);
            if (isConnected()) throw new IOException("旧独立服务尚未停止");
        }
    }

    private static void verifyPairing(Context context, Integer port) throws Exception {
        AppPrefs prefs = new AppPrefs(context);
        PrivilegeAdbManager adb = manager();
        PrivilegeAdbPairingCheckResult result;
        try {
            result = BuildersKt.<PrivilegeAdbPairingCheckResult>runBlocking(
                    EmptyCoroutineContext.INSTANCE, (scope, continuation) -> {
                        try { return adb.checkPairing(port, 15000L, continuation); }
                        catch (PrivilegeStartupException ex) { throw new IllegalStateException(ex); }
                    });
        } catch (Exception ex) {
            prefs.pairingUnavailable();
            throw ex;
        }
        switch (result.getStatus()) {
            case PAIRED:
                prefs.markAdbPaired();
                return;
            case UNPAIRED:
                prefs.rejectAdbIdentity();
                throw new IOException("系统拒绝 BerryFCM 当前密钥，请重新配对");
            default:
                prefs.pairingUnavailable();
                throw new IOException("ADB 认证未确认：" + result.getFailureMessage());
        }
    }

    private static PrivilegeServerInfo launch(Integer port) throws Exception {
        // One upstream identity for pairing, check and startup. With null port
        // upstream chooses both the reachable local address and port.
        PrivilegeAdbConnectionOptions options = new PrivilegeAdbConnectionOptions(
                port, PrivilegeAdbWirelessDebuggingControl.NEVER, false, 15000L, 3, 1000L);
        return BuildersKt.<PrivilegeServerInfo>runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> {
                    try { return KIT.startAdb(options, 20000L, ADB_NAME, line -> {
                        String next = startupTrace + line.getSource() + ": " + line.getMessage() + "\n";
                        startupTrace = next.length() > 7000 ? next.substring(next.length() - 7000) : next;
                    }, continuation); }
                    catch (Exception ex) { throw new IllegalStateException(ex); }
                });
    }

    static synchronized void start(Context context) throws Exception {
        start(context, true);
    }

    private static void start(Context context, boolean explicit) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("独立服务必须在后台启动");
        if (isConnected() && KIT.pingServer()) {
            autoAttempted = false;
            status = "独立服务运行中";
            startPrefs(context).edit().remove("last_error").apply();
            return;
        }
        if (!explicit && !canRecoverExisting(context))
            throw new IOException("等待真实配对认证或服务已被用户停止");
        if (!localNetworkAllowed(context))
            throw new IOException("Android 17 本地网络权限未授予，无法自动发现无线 ADB");
        autoAttempted = true;
        starting = true;
        status = "正在启动服务";
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
                AppPrefs prefs = new AppPrefs(context);
                int manual = prefs.wirelessConnectPort();
                prefs.wirelessConnectPort(0);
                // Pairing and connection are different ports. Prefer adbd's
                // current TLS connect property, then upstream NSD.
                int tls = manual > 0 ? 0 : systemConnectPort();
                Integer port = AdbConnectPort.select(manual, tls);
                if (port == null && wirelessDebuggingState(context) == 0) {
                    prefs.pairingUnavailable();
                    throw new IOException("系统无线调试未开启");
                }
                rememberLaunch(context);
                launched = true;
                startupTrace = "连接端口来源：" + (manual > 0 ? "手动连接端口" :
                        tls > 0 ? "当前系统 TLS 端口" : "mDNS 自动发现") + "\n";
                // Start using the resolved endpoint directly. A separate check opens a second ADB session.
                // Only check the identity on launch failure to classify rejection versus discovery failure.
                status = "正在连接 ADB 并启动服务";
                PrivilegeServerInfo result;
                try {
                    result = launch(port);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception failure) {
                    if (isConnected() && KIT.pingServer()) {
                        result = KIT.getServerState().getValue();
                        startupTrace += "\n启动响应异常，但已收到存活的 UID 2000 Binder";
                    } else {
                        try { verifyPairing(context, port); }
                        catch (InterruptedException interrupted) { throw interrupted; }
                        catch (Exception checkFailure) { startupTrace += "\n认证检查：" + errorChain(checkFailure); }
                        throw failure;
                    }
                }
                if (result == null || result.getUid() != 2000)
                    throw new IOException("独立服务未返回 UID 2000");
            }
            if (!isConnected() || !KIT.pingServer())
                throw new IOException("独立服务未回传有效的 UID 2000 Binder");
            if (launched) {
                new AppPrefs(context).markAdbPaired();
                startPrefs(context).edit().putString("last_result", "binder_ready").apply();
            }
            autoAttempted = false;
            status = "独立服务运行中";
            startPrefs(context).edit().remove("last_error").putString(TRACE_KEY, startupTrace).apply();
        } catch (InterruptedException ex) {
            if (launched) startPrefs(context).edit().putString("last_result", "interrupted").apply();
            Thread.currentThread().interrupt();
            status = "独立服务启动已中断";
            throw ex;
        } catch (Exception ex) {
            if (launched) startPrefs(context).edit().putString("last_result", "failed").apply();
            status = new AppPrefs(context).pairingRejected()
                    ? "ADB 授权已失效，请重新配对" : "服务启动失败";
            startupTrace += "\n启动失败："+ errorChain(ex) + "\n";
            startPrefs(context).edit().putString("last_error", errorChain(ex))
                    .putString(TRACE_KEY, startupTrace).apply();
            throw ex;
        } finally {
            starting = false;
        }
    }

    static String errorChain(Throwable error) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = error; t != null && out.length() < 1200; t = t.getCause()) {
            if (out.length() > 0) out.append(" / ");
            out.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            if (t.getCause() == t) break;
        }
        return out.toString();
    }

    static synchronized String execute(Context context, String command) throws Exception {
        if (new AppPrefs(context).servicePaused(AppPrefs.WIRELESS))
            throw new IOException("无线 ADB 服务已被用户手动停止");
        if (!isConnected() || !KIT.pingServer()) {
            // No per-sample ADB fallback, even if the starter is incompatible.
            AppPrefs prefs = new AppPrefs(context);
            if (AdbPairingService.isPairingActive() || (!prefs.adbPaired() && !hasStoredIdentity(context)) || prefs.pairingRejected())
                throw new IOException("请先完成 ADB 授权");
            if (wirelessDebuggingState(context) == 0)
                throw new IOException("服务未运行，请开启无线调试后启动服务");
            long remaining = cooldownRemaining(context);
            if (remaining > 0)
                throw new IOException("服务暂未恢复，" + ((remaining + 999) / 1000)
                        + " 秒后可再次尝试；也可在授权页面启动");
            // A failed attempt must not permanently disable recovery. Only a
            // lost Binder may open ADB again, at the persisted cooldown limit.
            autoAttempted = false;
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

    static String lastStartupError(Context context) {
        return startPrefs(context).getString("last_error", "");
    }

    // Read-only: sharing diagnostics must neither open ADB nor start a server.
    static String diagnostic(Context context) {
        SharedPreferences p = startPrefs(context);
        PrivilegeServerInfo info = KIT.getServerState().getValue();
        boolean live = info != null && info.getLifecycleBinder().isBinderAlive();
        long stamp = p.getLong("wall", 0);
        return "权限模式：" + new AppPrefs(context).mode()
                + "\nADB 认证证据：" + new AppPrefs(context).pairingEvidenceLabel()
                + "\nBinder：" + (live ? "存活 · UID=" + info.getUid() + " · PID=" + info.getPid() : "未连接")
                + "\n本 APP 进程 PID：" + android.os.Process.myPid()
                + "\n本地网络访问：" + (localNetworkAllowed(context) ? "系统允许" : "权限未授权")
                + "\n系统当前 TLS 连接端口：" + (systemConnectPort() > 0
                        ? String.valueOf(systemConnectPort()) : "不可读取，需 mDNS 自动发现")
                + "\n无线调试开关状态：" + (wirelessDebuggingState(context) < 0
                        ? "系统未提供可验证值" : wirelessDebuggingState(context) == 1 ? "开启" : "关闭")
                + "\n累计 ADB 启动尝试：" + p.getLong("attempt_count", 0)
                + "\n最近启动结果：" + p.getString("last_result", "none")
                + "\n最近启动请求时间（epoch ms）：" + stamp
                + "\n自动启动冷却剩余（秒）：" + ((cooldownRemaining(context) + 999) / 1000)
                + "\n最近启动失败原因：" + lastStartupError(context)
                + "\n最近独立服务启动日志：\n" + (startupTrace.isEmpty() ? p.getString(TRACE_KEY, "") : startupTrace)
                + "\n说明：启动尝试计数不是成功连接次数；日常 Binder 命令不增加此计数。";
    }

    static synchronized void stopManually(Context context) {
        new AppPrefs(context).servicePaused(AppPrefs.WIRELESS, true);
        stop();
    }

    static synchronized void stop() {
        try { if (KIT.pingServer()) KIT.shutdownServer(); }
        catch (RuntimeException ignored) { }
        autoAttempted = false;
        status = "独立服务已停止";
    }
}
