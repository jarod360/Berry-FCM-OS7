package top.zesun.berryfcm;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings action belongs to the FCM monitor, not AccessibilityService. */
public final class FcmTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }
    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = new AppPrefs(this).monitorEnabled();
        tile.setLabel("BerryFCM");
        if (Build.VERSION.SDK_INT >= 29)
            tile.setSubtitle(enabled ? FcmMonitorService.serviceStatus(this) + " · 点击检查" : "监控未开启");
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        if (new AppPrefs(this).monitorEnabled()) {
            FcmMonitorService.checkNow(this);
        } else {
            Intent open = new Intent(this, MonitorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
