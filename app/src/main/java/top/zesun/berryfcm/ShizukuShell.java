package top.zesun.berryfcm;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** Official Shizuku UserService; no local listening socket or held ADB transport. */
final class ShizukuShell implements PrivilegeShell {
    private static Binding binding;
    private final Context context;

    ShizukuShell(Context context) { this.context = context.getApplicationContext(); }

    static int authorizedUid() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.getVersion() < 13
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return -1;
            int uid = Shizuku.getUid();
            return uid == 0 || uid == 2000 ? uid : -1;
        } catch (RuntimeException error) { return -1; }
    }

    static synchronized void stopBinding() {
        if (binding == null) return;
        try { Shizuku.unbindUserService(binding.args, binding, true); }
        catch (RuntimeException ignored) { }
        binding = null;
    }

    static synchronized boolean isRunning() {
        return binding != null && binding.liveRemote() != null;
    }

    static String readiness() {
        try {
            if (!Shizuku.pingBinder()) return "请先安装并启动 Shizuku 服务";
            if (Shizuku.getVersion() < 13) return "需要 Shizuku v13 或更新版本";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                return "请在连接设置中授予 BerryFCM Shizuku 权限";
            return authorizedUid() >= 0 ? "Shizuku 已授权" : "Shizuku 身份不可用";
        } catch (RuntimeException error) { return "Shizuku 服务暂不可用"; }
    }

    private static final class Binding implements ServiceConnection {
        final Shizuku.UserServiceArgs args;
        final CountDownLatch ready = new CountDownLatch(1);
        final int uid;
        volatile IBerryShell remote;
        Binding(Context context, int uid) throws PackageManager.NameNotFoundException {
            this.uid = uid;
            int version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
            args = new Shizuku.UserServiceArgs(new ComponentName(context, BerryShellUserService.class))
                    .daemon(false).processNameSuffix("berry_shell").tag("berry_shell").version(version);
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IBerryShell.Stub.asInterface(binder);
            ready.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            ready.countDown();
        }
        IBerryShell liveRemote() {
            IBerryShell value = remote;
            return value != null && value.asBinder().isBinderAlive() ? value : null;
        }
    }

    private static synchronized IBerryShell connect(Context context) throws Exception {
        if (new AppPrefs(context).servicePaused(AppPrefs.SHIZUKU))
            throw new IllegalStateException("BerryFCM 的 Shizuku Shell 服务已被手动停止");
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Shizuku Shell requires a worker thread");
        int uid = authorizedUid();
        if (uid < 0) throw new IllegalStateException(readiness());
        IBerryShell existing = binding == null || binding.uid != uid ? null : binding.liveRemote();
        if (existing != null) return existing;
        if (binding != null) {
            try { Shizuku.unbindUserService(binding.args, binding, false); }
            catch (RuntimeException ignored) { }
            binding = null;
        }
        Binding next = new Binding(context, uid);
        try {
            Shizuku.bindUserService(next.args, next);
            if (!next.ready.await(15, TimeUnit.SECONDS))
                throw new IllegalStateException("Shizuku 独立 Shell 服务连接超时");
            IBerryShell result = next.liveRemote();
            if (result == null || authorizedUid() != uid)
                throw new IllegalStateException("Shizuku 服务连接或授权已失效");
            binding = next;
            return result;
        } catch (Exception error) {
            try { Shizuku.unbindUserService(next.args, next, false); }
            catch (RuntimeException ignored) { }
            throw error;
        }
    }

    @Override public String execute(String command) throws Exception {
        IBerryShell remote = connect(context);
        if (authorizedUid() < 0) throw new SecurityException(readiness());
        try { return remote.execute(command); }
        catch (RemoteException error) {
            throw new IllegalStateException("Shizuku 服务断开，下次检查将重新连接", error);
        }
    }

    @Override public void close() { }
}
