package top.zesun.berryfcm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AppPrefs prefs = new AppPrefs(context);
            if (prefs.autoRestore()) RestoreService.start(context, "boot");
            if (prefs.monitorEnabled()) FcmMonitorService.start(context);
        }
    }
}
