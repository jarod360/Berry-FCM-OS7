package top.zesun.berryfcm;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class RestoreTileService extends TileService {
    @Override public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile != null) { tile.setState(Tile.STATE_ACTIVE); tile.updateTile(); }
        unlockAndRun(() -> RestoreService.start(this, "tile"));
        if (tile != null) { tile.setState(Tile.STATE_INACTIVE); tile.updateTile(); }
    }
}
