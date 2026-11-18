package top.zesun.berryfcm;

import android.content.Context;

final class PrivilegeAccess {
    private PrivilegeAccess() {}

    // UI actions must not confuse saved pairing history with live ADB authorization.
    // Root and explicit legacy TCP have no persistent Binder; their next command
    // still performs real identity validation on a worker.
    static boolean readyForUserAction(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        if (AppPrefs.WIRELESS.equals(prefs.mode())) return PrivKitShell.isConnected();
        if (AppPrefs.SHIZUKU.equals(prefs.mode())) return ShizukuShell.authorizedUid() >= 0;
        return (AppPrefs.ROOT.equals(prefs.mode()) || AppPrefs.STATIC.equals(prefs.mode()))
                && prefs.mode().equals(prefs.shellMode()) && prefs.shellError().isEmpty()
                && !prefs.shellIdentity().isEmpty();
    }
    static String probe(Context context, String mode, String host, int port) throws Exception {
        if (AppPrefs.ROOT.equals(mode)) {
            try (PrivilegeShell shell = new RootShell(context)) {
                String identity = shell.execute("id").trim();
                if (!identity.matches("(?s)^uid=0(?:\\D.*)?$")) throw new IllegalStateException("Root 未授权");
                return identity;
            }
        }
        if (AppPrefs.SHIZUKU.equals(mode)) {
            try (PrivilegeShell shell = new ShizukuShell(context)) {
                String identity = shell.execute("id").trim();
                if (!identity.matches("(?s)^uid=(0|2000)(?:\\D.*)?$"))
                    throw new IllegalStateException("Shizuku Shell 身份异常");
                return identity;
            }
        }
        if (AppPrefs.WIRELESS.equals(mode)) {
            PrivKitShell.start(context);
            return PrivKitShell.execute(context, "id");
        }
        AdbConnectionManager adb = AdbConnectionManager.get(context);
        return adb.executeOnce(context, mode, host, port, "id");
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
        if (AppPrefs.SHIZUKU.equals(mode)) {
            ShizukuShell shell = new ShizukuShell(context);
            try {
                String identity = shell.execute("id").trim();
                if (!identity.matches("(?s)^uid=(0|2000)(?:\\D.*)?$"))
                    throw new IllegalStateException("Shizuku Shell 身份异常");
                if (mode.equals(prefs.mode())) prefs.shellVerified(identity);
                return shell;
            } catch (Exception error) {
                if (mode.equals(prefs.mode())) prefs.shellFailed(error.getMessage());
                throw error;
            }
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
