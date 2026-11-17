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
    static String gmsDoze(android.content.Context context, PrivilegeShell shell, boolean allow) throws Exception {
        android.content.SharedPreferences ownership = context.getSharedPreferences(
                "berry_fcm_policy_ownership", android.content.Context.MODE_PRIVATE);
        String before = shell.execute("cmd deviceidle whitelist 2>&1");
        boolean present = before.contains(",com.google.android.gms");
        if (allow && present) return "GMS 已经位于 Doze 白名单，本次没有修改，不记录为 BerryFCM 添加";
        if (!allow && !ownership.getBoolean("gms_doze_owned", false))
            return "拒绝撤销：该白名单不是 BerryFCM 添加的";
        shell.execute("cmd deviceidle whitelist "
                + (allow ? "+" : "-") + "com.google.android.gms 2>&1");
        String after = shell.execute("cmd deviceidle whitelist 2>&1");
        boolean nowPresent = after.contains(",com.google.android.gms");
        if (nowPresent != allow) throw new IllegalStateException("Doze 白名单未通过回读验证");
        ownership.edit().putBoolean("gms_doze_owned", allow).apply();
        return allow ? "GMS 已加入 Doze 白名单（由 BerryFCM 添加，可撤销）"
                     : "已撤销 BerryFCM 添加的 GMS Doze 白名单";
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
