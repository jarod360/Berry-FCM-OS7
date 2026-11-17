package top.zesun.berryfcm;

import android.app.Activity;
import android.app.ActivityManager;
import java.util.List;

final class RecentTasks {
    private RecentTasks() {}
    static boolean apply(Activity activity, boolean hide) {
        ActivityManager manager = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
        if (manager == null) return false;
        List<ActivityManager.AppTask> tasks = manager.getAppTasks();
        if (tasks == null || tasks.isEmpty()) return false;
        try {
            for (ActivityManager.AppTask task : tasks) task.setExcludeFromRecents(hide);
            return true;
        } catch (RuntimeException error) { return false; }
    }
}
