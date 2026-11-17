package top.zesun.berryfcm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Auto-restores only the user-enabled FCM monitor and application guard. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        AppPrefs prefs = new AppPrefs(context);
        if (prefs.startOnBoot() && (prefs.monitorEnabled() || prefs.guardEnabled()))
            FcmMonitorService.start(context);
    }
}
