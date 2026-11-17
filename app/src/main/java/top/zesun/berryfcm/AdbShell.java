package top.zesun.berryfcm;

import android.content.Context;

final class AdbShell implements PrivilegeShell {
    private final AdbConnectionManager adb;
    AdbShell(Context context) throws Exception { adb = AdbConnectionManager.get(context); }
    @Override public String execute(String command) throws Exception { return adb.shell(command); }
    @Override public void close() { adb.disconnectSession(); }
}
