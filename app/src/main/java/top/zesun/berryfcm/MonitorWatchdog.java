package top.zesun.berryfcm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.PowerManager;

/**
 * Backup wake-up path when a foreground monitor process stops or stalls.
 * In Doze, Android may delay a requested alarm for several minutes.
 * In smart mode, an already-dispatched check receives a bounded CPU lease;
 * in continuous mode the service also holds a long-lived CPU lease.
 */
public final class MonitorWatchdog extends BroadcastReceiver {
    private static final String ACTION = "top.zesun.berryfcm.MONITOR_WATCHDOG";
    private static PowerManager.WakeLock handoff;

    /** Holds the CPU only until the service worker acquires its bounded lease. */
    private static synchronized void holdForService(Context context) {
        releaseHandoff();
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power == null) return;
            handoff = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BerryFCM:watchdog-handoff");
            handoff.setReferenceCounted(false);
            handoff.acquire(90000L);
        } catch (RuntimeException error) {
            handoff = null;
            FcmMonitorService.logRuntime(context, "watchdog_wakelock_failed",
                    error.getClass().getSimpleName());
        }
    }
    static synchronized void releaseHandoff() {
        if (handoff == null) return;
        try { if (handoff.isHeld()) handoff.release(); }
        catch (RuntimeException ignored) { }
        finally { handoff = null; }
    }

    private static PendingIntent operation(Context context, int flags) {
        Intent intent = new Intent(context, MonitorWatchdog.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, 7313, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.monitorEnabled() && !prefs.guardEnabled()) {
            cancel(context);
            return;
        }
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        long due = SystemClock.elapsedRealtime() + prefs.watchdogMinutes() * 60_000L;
        android.content.SharedPreferences state =
                context.getSharedPreferences("berry_fcm_watchdog", Context.MODE_PRIVATE);
        if (operation(context, PendingIntent.FLAG_NO_CREATE) != null
                && state.getLong("due_elapsed", -1L) > 0) return;
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                due, operation(context, PendingIntent.FLAG_UPDATE_CURRENT));
        state.edit().putLong("due_elapsed", due).apply();
    }

    static void reschedule(Context context) {
        cancel(context);
        schedule(context);
    }

    static void cancel(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent pending = operation(context, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            if (alarms != null) alarms.cancel(pending);
            pending.cancel();
        }
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
        context.getSharedPreferences("berry_fcm_watchdog", Context.MODE_PRIVATE)
                .edit().remove("due_elapsed").apply();
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
        holdForService(context);
        if (!FcmMonitorService.startFromWatchdog(context)) {
            releaseHandoff();
            FcmMonitorService.logRuntime(context, "watchdog_failed", "foreground_service_start_rejected");
        }
    }
}
