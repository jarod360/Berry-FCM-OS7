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
    // A first pairing exchange is not authorization. Allow background startup
    // only with a live service or a previously verified transport that can
    // legitimately recover. This preserves recovery for established users.
    // Do not gate this on battery optimization, OEM autostart/associated launch,
    // high power, recents lock, notifications or Quick Settings tile consent.
    static boolean canRunInBackground(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        if (readyForUserAction(context)) return true;
        return AppPrefs.WIRELESS.equals(prefs.mode())
                && prefs.adbPaired() && !prefs.pairingRejected()
                && AppPrefs.WIRELESS.equals(prefs.shellMode())
                && prefs.shellIdentity().matches("(?s)^uid=2000(?:\\D.*)?$")
                && !AdbPairingService.isPairingActive()
                && PrivKitShell.wirelessDebuggingState(context) == 1;
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
            return requireShellIdentity(PrivKitShell.execute(context, "id"), false);
        }
        if (!AppPrefs.STATIC.equals(mode)) throw new IllegalStateException("不支持的授权方式");
        AdbConnectionManager adb = AdbConnectionManager.get(context);
        return requireShellIdentity(adb.executeOnce(context, mode, host, port, "id"), false);
    }

    private static String requireShellIdentity(String result, boolean root) {
        String identity = result == null ? "" : result.trim();
        if (!identity.matches(root ? "(?s)^uid=0(?:\\D.*)?$" : "(?s)^uid=2000(?:\\D.*)?$"))
            throw new IllegalStateException("Shell 身份回读不匹配，未切换授权");
        return identity;
    }

    static PrivilegeShell open(Context context) throws Exception {
        AppPrefs prefs = new AppPrefs(context);
        String mode = prefs.mode();
        if (AppPrefs.ROOT.equals(mode)) {
            RootShell shell = new RootShell(context);
            try {
                String identity = requireShellIdentity(shell.execute("id"), true);
                prefs.shellVerified(identity);
                return shell;
            } catch (Exception error) {
                shell.close();
                prefs.shellFailed(error.getMessage());
                throw error;
            }
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
                String identity = requireShellIdentity(shell.execute("id"), false);
                if (mode.equals(prefs.mode())) prefs.shellVerified(identity);
                return shell;
            } catch (Exception error) {
                shell.close();
                if (mode.equals(prefs.mode())) prefs.shellFailed(error.getMessage());
                throw error;
            }
        }
        throw new IllegalStateException("请先在授权页面选择 Root 或 ADB 模式");
    }
}
