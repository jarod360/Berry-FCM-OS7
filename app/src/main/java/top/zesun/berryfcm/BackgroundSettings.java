package top.zesun.berryfcm;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/** Small, explicit set of ADB/Root-backed background policies; no vendor-private writes. */
final class BackgroundSettings {
    private static final String SELF = "top.zesun.berryfcm";
    private BackgroundSettings() { }

    static String optimize(PrivilegeShell shell) throws Exception {
        String command =
                "echo '=== BerryFCM DeviceIdle ==='; " +
                "cmd deviceidle whitelist +" + SELF + " 2>&1; " +
                "echo '=== BerryFCM background AppOps ==='; " +
                "cmd appops set " + SELF + " RUN_ANY_IN_BACKGROUND allow 2>&1; " +
                "cmd appops set " + SELF + " RUN_IN_BACKGROUND allow 2>&1; " +
                "echo '=== Google FCM Doze exemption ==='; " +
                "cmd deviceidle whitelist +com.google.android.gms 2>&1; " +
                "echo '=== Verify ==='; " +
                "cmd deviceidle whitelist 2>&1 | grep -E '(" + SELF + "|com.google.android.gms)' || true; " +
                "cmd appops get " + SELF + " RUN_ANY_IN_BACKGROUND 2>&1; " +
                "cmd appops get " + SELF + " RUN_IN_BACKGROUND 2>&1";
        return shell.execute(command).trim();
    }

    static boolean ignoringBatteryOptimizations(Activity activity) {
        PowerManager power = activity.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(activity.getPackageName());
    }

    static void requestBatteryExemption(Activity activity) {
        if (ignoringBatteryOptimizations(activity)) {
            openAppDetails(activity);
            return;
        }
        try {
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(request);
        } catch (RuntimeException ignored) {
            openBatterySettings(activity);
        }
    }

    static void openBatterySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException ignored) {
            openAppDetails(activity);
        }
    }

    static void openAutostart(Activity activity) {
        // OriginOS varies by build; only launch known exported settings, never claim to
        // change a manufacturer flag unless the user verifies it in the settings UI.
        try {
            Intent vivo = new Intent();
            vivo.setClassName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
            activity.startActivity(vivo);
        } catch (RuntimeException first) {
            try {
                activity.startActivity(new Intent("com.iqoo.secure.BGSTARTUPMANAGER"));
            } catch (RuntimeException second) {
                openAppDetails(activity);
            }
        }
    }

    static void openAppDetails(Activity activity) {
        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }
}
