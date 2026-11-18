package top.zesun.berryfcm;

import android.content.Context;

/** Keep each manual operation bound to its configured transport. */
final class AdbShell implements PrivilegeShell {
    private final AdbConnectionManager adb;
    private final Context context;
    private final String mode, host;
    private final int port;
    AdbShell(Context context, String mode, String host, int port) throws Exception {
        this.context = context.getApplicationContext();
        this.mode = mode;
        this.host = host;
        this.port = port;
        adb = AdbConnectionManager.get(context);
    }
    @Override public String execute(String command) throws Exception {
        synchronized (adb) {
            try {
                boolean connected = AppPrefs.WIRELESS.equals(mode)
                        ? adb.autoConnectLocal(context) : adb.connectStatic(host, port);
                if (!connected) throw new IllegalStateException("ADB 暂未连接");
                String identity = adb.shell("id").trim();
                if (!identity.matches("(?s)^uid=2000(?:\\D.*)?$"))
                    throw new IllegalStateException("ADB Shell 身份异常");
                return "id".equals(command) ? identity : adb.shell(command);
            } catch (Exception error) {
                adb.disconnectSession();
                throw error;
            }
        }
    }
    // The connection manager owns the shared ADB transport.
    @Override public void close() { }
}
