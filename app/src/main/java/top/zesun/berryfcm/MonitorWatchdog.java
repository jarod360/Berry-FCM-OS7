package top.zesun.berryfcm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Backup wake-up path when a foreground monitor process stops or stalls.
 * In Doze, Android may throttle a 60-second request for several minutes.
 * The service's CPU wake lock and in-process checks are the primary path.
 */
public final class MonitorWatchdog extends BroadcastReceiver {
    private static final String ACTION = "top.zesun.berryfcm.MONITOR_WATCHDOG";
    private static final long DELAY_MS = 60_000L;

    private static PendingIntent operation(Context context) {
        Intent intent = new Intent(context, MonitorWatchdog.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, 7313, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) {
            cancel(context);
            return;
        }
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        // Request 60 seconds, never claim it fires in 60 seconds under Doze.
        long due = SystemClock.elapsedRealtime() + DELAY_MS;
        context.getSharedPreferences("berry_fcm_watchdog", Context.MODE_PRIVATE)
                .edit().putLong("due_elapsed", due).apply();
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                due, operation(context));
    }

    static void cancel(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(operation(context));
        context.getSharedPreferences("berry_fcm_watchdog", Context.MODE_PRIVATE)
                .edit().remove("due_elapsed").apply();
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) {
            cancel(context);
            return;
        }
        // Capture the previous scheduled time before re-arming.
        long due = context.getSharedPreferences("berry_fcm_watchdog", Context.MODE_PRIVATE)
                .getLong("due_elapsed", -1L);
        long lateness = due > 0 ? SystemClock.elapsedRealtime() - due : -1L;
        // Re-arm before attempting any Shell, Binder, or foreground service.
        schedule(context);
        long sampleAge = FcmMonitorService.sampleAgeSeconds(context);
        boolean missing = !FcmMonitorService.isRunning();
        boolean stalled = prefs.monitorEnabled() && (sampleAge < 0 || sampleAge > 60);
        FcmMonitorService.logRuntime(context, "watchdog_fired",
                "service_running=" + !missing + "; sample_age_s=" + sampleAge
                + "; alarm_late_ms=" + lateness
                + "; needs_check=" + (missing || stalled));
        if (!missing && !stalled) return;
        if (!PrivilegeAccess.canRunInBackground(context)) {
            FcmMonitorService.logRuntime(context, "watchdog_skipped", "authorization_unavailable");
            return;
        }
        if (!FcmMonitorService.startFromWatchdog(context))
            FcmMonitorService.logRuntime(context, "watchdog_failed", "foreground_service_start_rejected");
    }
}
