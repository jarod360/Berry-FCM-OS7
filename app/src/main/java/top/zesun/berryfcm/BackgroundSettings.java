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

    // PrivilegeShell returns combined text. Absence in an error message is not
    // evidence that a policy is absent. Only a successful read can authorize a write.
    private static String checkedRead(PrivilegeShell shell, String command) throws Exception {
        String raw = shell.execute(command + " 2>&1 && echo BERRY_POLICY_READ_OK");
        if (!raw.trim().endsWith("BERRY_POLICY_READ_OK")
                || java.util.regex.Pattern.compile("(?i)permission denial|permission denied|securityexception|unknown command|not allowed|can't find service").matcher(raw).find())
            throw new IllegalStateException("系统策略无法读取，未继续修改");
        return raw.substring(0, raw.lastIndexOf("BERRY_POLICY_READ_OK")).trim();
    }

    private static boolean dozePresent(PrivilegeShell shell, String pkg) throws Exception {
        String rows = checkedRead(shell, "cmd deviceidle whitelist");
        // AOSP prints category,package,appId; match the complete package, not a prefix.
        for (String line : rows.split("\\r?\\n")) {
            String[] cols = line.trim().split(",");
            if (cols.length >= 2 && pkg.equals(cols[1].trim())) return true;
        }
        return false;
    }

    // Only modify a setting after recording its previous state. Never guess an
    // AppOps default or overwrite a setting whose current mode is unreadable.
    static String optimize(android.content.Context context, PrivilegeShell shell) throws Exception {
        android.content.SharedPreferences own = context.getSharedPreferences(
                "berry_fcm_policy_ownership", android.content.Context.MODE_PRIVATE);
        StringBuilder report = new StringBuilder();
        if (dozePresent(shell, SELF)) {
            report.append("BerryFCM 休眠限制：系统已允许，无需修改\n");
        } else {
            if (!own.edit().putBoolean("self_doze_owned", true).commit())
                throw new IllegalStateException("无法保存 Doze 回滚记录");
            shell.execute("cmd deviceidle whitelist +" + SELF + " 2>&1");
            if (!dozePresent(shell, SELF))
                throw new IllegalStateException("BerryFCM Doze 放行未通过回读");
            report.append("BerryFCM 休眠限制：已放行（可撤销）\n");
        }
        for (String op : new String[]{"RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND"}) {
            String mode = readAppOp(shell, op);
            if (mode == null) {
                report.append("RUN_ANY_IN_BACKGROUND".equals(op) ? "后台活动限制" : "后台运行限制")
                        .append("：无法核实当前状态，未修改\n");
                continue;
            }
            if (!"allow".equals(mode)) {
                String key = "self_op_previous_" + op;
                if (!own.contains(key) && !own.edit().putString(key, mode).commit())
                    throw new IllegalStateException("无法记录 " + op + " 原始状态");
                shell.execute("cmd appops set " + SELF + " " + op + " allow 2>&1");
                if (!"allow".equals(readAppOp(shell, op)))
                    throw new IllegalStateException(op + " 放行未通过回读");
            }
            report.append("RUN_ANY_IN_BACKGROUND".equals(op) ? "后台活动限制" : "后台运行限制")
                    .append("：已允许")
                    .append(own.contains("self_op_previous_" + op) ? "（可撤销）" : "（保持原状态）")
                    .append("\n");
        }
        return report.toString().trim();
    }

    static String restoreSelf(android.content.Context context, PrivilegeShell shell) throws Exception {
        android.content.SharedPreferences own = context.getSharedPreferences(
                "berry_fcm_policy_ownership", android.content.Context.MODE_PRIVATE);
        StringBuilder report = new StringBuilder();
        if (own.getBoolean("self_doze_owned", false)) {
            if (dozePresent(shell, SELF)) {
                shell.execute("cmd deviceidle whitelist -" + SELF + " 2>&1");
                if (dozePresent(shell, SELF))
                    throw new IllegalStateException("撤销 BerryFCM Doze 放行失败");
            }
            own.edit().remove("self_doze_owned").apply();
            report.append("BerryFCM 休眠限制：已撤销本应用放行\n");
        }
        for (String op : new String[]{"RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND"}) {
            String key = "self_op_previous_" + op;
            String previous = own.getString(key, null);
            if (previous == null) continue;
            String now = readAppOp(shell, op);
            if (now == null) throw new IllegalStateException(op + " 无法回读，保留回滚记录");
            if ("allow".equals(now)) {
                shell.execute("cmd appops set " + SELF + " " + op + " " + previous + " 2>&1");
                if (!previous.equals(readAppOp(shell, op)))
                    throw new IllegalStateException(op + " 恢复未通过回读");
            } else {
                report.append("RUN_ANY_IN_BACKGROUND".equals(op) ? "后台活动限制" : "后台运行限制")
                        .append("：已被其他操作修改，没有覆盖\n");
            }
            own.edit().remove(key).apply();
            report.append("RUN_ANY_IN_BACKGROUND".equals(op) ? "后台活动限制" : "后台运行限制")
                    .append("：原始状态恢复记录已处理\n");
        }
        if (report.length() == 0)
            return "没有本版 BerryFCM 记录的可撤销策略；旧版未记录原状态的修改不能安全推测并恢复";
        return report.toString().trim();
    }

    private static String readAppOp(PrivilegeShell shell, String op) throws Exception {
        String output = checkedRead(shell, "cmd appops get " + SELF + " " + op);
        java.util.regex.Matcher found = java.util.regex.Pattern.compile(
                "(?i)\\b" + op + "\\s*:\\s*(allow|ignore|deny|default|foreground)\\b").matcher(output);
        if (found.find()) return found.group(1).toLowerCase(java.util.Locale.ROOT);
        return output.contains("No operations") ? "default" : null;
    }

    static String gmsDoze(android.content.Context context, PrivilegeShell shell, boolean allow) throws Exception {
        android.content.SharedPreferences ownership = context.getSharedPreferences(
                "berry_fcm_policy_ownership", android.content.Context.MODE_PRIVATE);
        boolean present = dozePresent(shell, GuardOps.GMS);
        if (allow && present) return "GMS 已经位于 Doze 白名单，本次没有修改，不记录为 BerryFCM 添加";
        if (!allow && !ownership.getBoolean("gms_doze_owned", false))
            return "拒绝撤销：该白名单不是 BerryFCM 添加的";
        if (allow && !ownership.edit().putBoolean("gms_doze_owned", true).commit())
            throw new IllegalStateException("无法保存 GMS Doze 撤销记录");
        shell.execute("cmd deviceidle whitelist "
                + (allow ? "+" : "-") + "com.google.android.gms 2>&1");
        boolean nowPresent = dozePresent(shell, GuardOps.GMS);
        if (nowPresent != allow) throw new IllegalStateException("Doze 白名单未通过回读验证");
        ownership.edit().putBoolean("gms_doze_owned", allow).apply();
        return allow ? "GMS 已加入 Doze 白名单（由 BerryFCM 添加，可撤销）"
                     : "已撤销 BerryFCM 添加的 GMS Doze 白名单";
    }
    static String gmsBackgroundNetwork(android.content.Context context,
                                        PrivilegeShell shell, boolean allow) throws Exception {
        String uidOutput = checkedRead(shell, "cmd package list packages -U com.google.android.gms");
        java.util.regex.Matcher uidMatch = java.util.regex.Pattern.compile(
                "uid:([0-9]+)").matcher(uidOutput);
        if (!uidMatch.find()) throw new IllegalStateException("无法通过 PackageManager 取得 GMS UID");
        String uid = uidMatch.group(1);
        android.content.SharedPreferences own = context.getSharedPreferences(
                "berry_fcm_policy_ownership", android.content.Context.MODE_PRIVATE);
        String before = checkedRead(shell, "cmd netpolicy list restrict-background-whitelist");
        boolean exists = containsUid(before, uid);
        if (allow && exists) return "GMS 已经放行后台计量数据，未修改现有策略";
        if (!allow && (!own.getBoolean("net_owned", false)
                || !uid.equals(own.getString("net_uid", ""))))
            return "拒绝撤销：该 UID 的后台数据白名单并非由 BerryFCM 添加";
        String operation = "cmd netpolicy " + (allow ? "add" : "remove")
                + " restrict-background-whitelist " + uid;
        if (allow && !own.edit().putBoolean("net_owned", true).putString("net_uid", uid).commit())
            throw new IllegalStateException("无法保存 GMS 网络策略撤销记录");
        shell.execute(operation);
        String after = checkedRead(shell, "cmd netpolicy list restrict-background-whitelist");
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

    static String backgroundRestriction(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT < 28) return "系统不支持读取";
        android.app.ActivityManager am = activity.getSystemService(android.app.ActivityManager.class);
        if (am == null) return "无法读取";
        return am.isBackgroundRestricted() ? "已限制" : "未限制（不代表厂商高耗电已开启）";
    }

    static String monitorNotificationStatus(Activity activity) {
        android.app.NotificationManager manager = activity.getSystemService(android.app.NotificationManager.class);
        if (manager == null) return "无法读取";
        if (!manager.areNotificationsEnabled()) return "系统通知已关闭";
        android.app.NotificationChannel channel = manager.getNotificationChannel(
                "berry_fcm_monitor_quiet");
        if (channel == null) return "尚未创建";
        return channel.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE ? "已关闭" : "已开启";
    }
    static void notifications(Activity activity) {
        try { activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())); }
        catch (RuntimeException ex) { details(activity); }
    }
    static void monitorNotifications(Activity activity) {
        String channel = "berry_fcm_monitor_quiet";
        android.app.NotificationManager manager = activity.getSystemService(android.app.NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(channel) == null) { notifications(activity); return; }
        try { activity.startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)); }
        catch (RuntimeException ex) { notifications(activity); }
    }
    private static boolean openComponent(Activity activity, String pkg, String cls) {
        try { activity.startActivity(new Intent().setClassName(pkg, cls)); return true; }
        catch (RuntimeException ex) { return false; }
    }
    static void powerSettings(Activity activity) {
        // Vivo/iQOO power management entry points used by existing open-source apps.
        if (openComponent(activity, "com.vivo.abeui", "com.vivo.abeui.highpower.ExcessivePowerManagerActivity")
                || openComponent(activity, "com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                || openComponent(activity, "com.vivo.abe", "com.vivo.abe.unifiedpower.HighPowerConsumptionActivity")) return;
        try { activity.startActivity(new Intent("android.intent.action.POWER_USAGE_SUMMARY")); }
        catch (RuntimeException ex) { details(activity); }
    }
    static void associatedStart(Activity activity) {
        if (openComponent(activity, "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
                || openComponent(activity, "com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")) return;
        // Some ROM versions expose both controls in the same startup-management screen.
        autostart(activity);
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
            if (openComponent(activity, "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")) return;
            try { activity.startActivity(new Intent("com.iqoo.secure.BGSTARTUPMANAGER")); }
            catch (RuntimeException ignoredAgain) { details(activity); }
        }
    }
    static void details(Activity activity) {
        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }
}
