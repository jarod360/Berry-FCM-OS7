package top.zesun.berryfcm;

import android.content.Context;

final class PrivilegeAccess {
    private PrivilegeAccess() {}
    static PrivilegeShell open(Context context) throws Exception {
        AppPrefs prefs = new AppPrefs(context);
        String mode = prefs.mode();
        if (AppPrefs.ROOT.equals(mode)) {
            RootShell shell = new RootShell();
            String identity = shell.execute("id");
            if (!identity.contains("uid=0")) throw new IllegalStateException("Root 未授权");
            return shell;
        }
        if (AppPrefs.WIRELESS.equals(mode)) {
            if (!AdbConnectionManager.get(context).autoConnectLocal(context))
                throw new IllegalStateException("无线 ADB 未连接；请先在授权页面通过通知栏配对");
            return new AdbShell(context);
        }
        if (AppPrefs.STATIC.equals(mode)) {
            if (!AdbConnectionManager.get(context).connectStatic(prefs.host(), prefs.staticPort()))
                throw new IllegalStateException("静态 ADB 未连接；请核对设备 IP 和端口");
            return new AdbShell(context);
        }
        throw new IllegalStateException("请先在授权页面选择 Root 或 ADB 模式");
    }
}
