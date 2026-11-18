package top.zesun.berryfcm;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings action belongs to the FCM monitor, not AccessibilityService. */
public final class FcmTileService extends TileService {
    // Manifest default is disabled. Only an explicit settings action exposes
    // the tile; declaring the service is not user consent to add a tile.
    static boolean offered(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(
                new ComponentName(context, FcmTileService.class))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
    static void noteAdded(Context context, boolean added) {
        context.getSharedPreferences("berry_tile", Context.MODE_PRIVATE).edit().putBoolean("added", added).apply();
    }
    static String displayStatus(Context context) {
        if (!offered(context)) return "未启用";
        return context.getSharedPreferences("berry_tile", Context.MODE_PRIVATE).getBoolean("added", false)
                ? "已添加（系统确认）" : "未确认添加";
    }
    static void setOffered(Context context, boolean enabled) {
        if (!enabled) noteAdded(context, false);
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, FcmTileService.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    @Override public void onTileAdded() {
        super.onTileAdded();
        noteAdded(this, true);
    }
    @Override public void onTileRemoved() {
        super.onTileRemoved();
        noteAdded(this, false);
    }
    @Override public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }
    private boolean authorized() {
        return PrivilegeAccess.readyForUserAction(this);
    }
    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean ready = authorized();
        boolean enabled = ready && new AppPrefs(this).monitorEnabled() && FcmMonitorService.isRunning();
        tile.setLabel("BerryFCM");
        if (Build.VERSION.SDK_INT >= 29)
            tile.setSubtitle(!ready ? "需要授权" : enabled ? "监控运行中 · 点击检查" : "监控未运行");
        tile.setState(!ready ? Tile.STATE_UNAVAILABLE : enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        boolean ready = authorized();
        if (!ready) { refreshTile(); return; }
        if (ready && new AppPrefs(this).monitorEnabled() && FcmMonitorService.isRunning()) {
            FcmMonitorService.checkNow(this);
        } else {
            Intent open = new Intent(this, ready ? MonitorActivity.class : AuthActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pending);
            } else {
                startActivityAndCollapse(open);
            }
        }
        refreshTile();
    }
}
