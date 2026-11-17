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
    static String gmsBackgroundNetwork(android.content.Context context,
                                        PrivilegeShell shell, boolean allow) throws Exception {
        String uidOutput = shell.execute("cmd package list packages -U com.google.android.gms 2>&1");
        java.util.regex.Matcher uidMatch = java.util.regex.Pattern.compile(
                "uid:([0-9]+)").matcher(uidOutput);
        if (!uidMatch.find()) throw new IllegalStateException("无法通过 PackageManager 取得 GMS UID");
        String uid = uidMatch.group(1);
        android.content.SharedPreferences own = context.getSharedPreferences(
                "berry_fcm_policy_ownership", android.content.Context.MODE_PRIVATE);
        String before = shell.execute("cmd netpolicy list restrict-background-whitelist 2>&1");
        boolean exists = containsUid(before, uid);
        if (allow && exists) return "GMS 已经放行后台计量数据，未修改现有策略";
        if (!allow && (!own.getBoolean("net_owned", false)
                || !uid.equals(own.getString("net_uid", ""))))
            return "拒绝撤销：该 UID 的后台数据白名单并非由 BerryFCM 添加";
        String operation = "cmd netpolicy " + (allow ? "add" : "remove")
                + " restrict-background-whitelist " + uid;
        shell.execute(operation);
        String after = shell.execute("cmd netpolicy list restrict-background-whitelist 2>&1");
        if (containsUid(after, uid) != allow)
            throw new IllegalStateException("GMS 后台数据白名单复检未通过");
        own.edit().putBoolean("net_owned", allow)
                .putString("net_uid", allow ? uid : "").apply();
        return allow ? "GMS UID " + uid + " 后台计量数据已放行（可撤销）"
                     : "已撤销 BerryFCM 添加的 GMS 后台数据放行";
    }

    private static boolean containsUid(String text, String uid) {
        return java.util.regex.Pattern.compile("(?<![0-9])"
                + java.util.regex.Pattern.quote(uid) + "(?![0-9])").matcher(text).find();
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
