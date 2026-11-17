package top.zesun.berryfcm;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/** Standard Android settings and bounded privilege operations. */
final class BackgroundSettings {
    private static final String SELF = "top.zesun.berryfcm";
    private BackgroundSettings() {}

    static String optimize(PrivilegeShell shell) throws Exception {
        // Only change our own background policy. Google Play services is a separate opt-in.
        String command = "cmd deviceidle whitelist +" + SELF + " 2>&1; "
                + "cmd appops set " + SELF + " RUN_ANY_IN_BACKGROUND allow 2>&1; "
                + "cmd appops set " + SELF + " RUN_IN_BACKGROUND allow 2>&1; "
                + "echo '=== 已回读的状态 ==='; "
                + "cmd deviceidle whitelist | grep -F '" + SELF + "' || true; "
                + "cmd appops get " + SELF + " RUN_ANY_IN_BACKGROUND 2>&1; "
                + "cmd appops get " + SELF + " RUN_IN_BACKGROUND 2>&1";
        return shell.execute(command).trim();
    }
    static String gmsDoze(PrivilegeShell shell, boolean allow) throws Exception {
        String cmd = "cmd deviceidle whitelist "
                + (allow ? "+" : "-") + "com.google.android.gms 2>&1; "
                + "cmd deviceidle whitelist | grep -F com.google.android.gms || true";
        return shell.execute(cmd).trim();
    }
    static boolean batteryExempt(Activity activity) {
        PowerManager service = activity.getSystemService(PowerManager.class);
        return service != null && service.isIgnoringBatteryOptimizations(activity.getPackageName());
    }
    static void requestBattery(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException error) { batteryList(activity); }
    }
    static void batteryList(Activity activity) {
        try { activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        catch (RuntimeException error) { details(activity); }
    }
    static void autostart(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
            activity.startActivity(intent);
        } catch (RuntimeException ignored) {
            try { activity.startActivity(new Intent("com.iqoo.secure.BGSTARTUPMANAGER")); }
            catch (RuntimeException ignoredAgain) { details(activity); }
        }
    }
    static void details(Activity activity) {
        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }
}
