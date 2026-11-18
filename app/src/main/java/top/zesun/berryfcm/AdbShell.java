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
        return adb.executeOnce(context, mode, host, port, command);
    }
    // executeOnce releases the transport even when a command fails.
    @Override public void close() { }
}
