package top.zesun.berryfcm;

import android.content.Context;

final class PrivilegeAccess {
    private PrivilegeAccess() {}
    static String probe(Context context, String mode, String host, int port) throws Exception {
        if (AppPrefs.ROOT.equals(mode)) {
            try (PrivilegeShell shell = new RootShell(context)) {
                String identity = shell.execute("id").trim();
                if (!identity.matches("(?s)^uid=0(?:\\D.*)?$")) throw new IllegalStateException("Root 未授权");
                return identity;
            }
        }
        AdbConnectionManager adb = AdbConnectionManager.get(context);
        synchronized (adb) {
            boolean connected = AppPrefs.WIRELESS.equals(mode) ? adb.autoConnectLocal(context)
                    : AppPrefs.STATIC.equals(mode) && adb.connectStatic(host, port);
            if (!connected) throw new IllegalStateException("候选 ADB 未连接");
            String identity = adb.shell("id").trim();
            if (!identity.matches("(?s)^uid=2000(?:\\D.*)?$"))
                throw new IllegalStateException("ADB Shell 身份异常");
            return identity;
        }
    }

    static PrivilegeShell open(Context context) throws Exception {
        AppPrefs prefs = new AppPrefs(context);
        String mode = prefs.mode();
        if (AppPrefs.ROOT.equals(mode)) {
            RootShell shell = new RootShell(context);
            String identity = shell.execute("id").trim();
            if (!identity.contains("uid=0")) throw new IllegalStateException("Root 未授权");
            prefs.shellVerified(identity);
            return shell;
        }
        if (AppPrefs.WIRELESS.equals(mode) || AppPrefs.STATIC.equals(mode)) {
            AdbShell shell = new AdbShell(context, mode, prefs.host(), prefs.staticPort());
            try {
                String identity = shell.execute("id").trim();
                if (mode.equals(prefs.mode())) prefs.shellVerified(identity);
                return shell;
            } catch (Exception error) {
                if (mode.equals(prefs.mode())) prefs.shellFailed(error.getMessage());
                throw error;
            }
        }
        throw new IllegalStateException("请先在授权页面选择 Root 或 ADB 模式");
    }
}
