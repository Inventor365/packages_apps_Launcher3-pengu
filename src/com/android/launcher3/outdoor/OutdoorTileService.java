package com.android.launcher3.outdoor;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class OutdoorTileService extends TileService {

    private final Runnable mOnChanged = this::updateTile;

    @Override
    public void onStartListening() {
        OutdoorState.get(this).addListener(mOnChanged);
        updateTile();
    }

    @Override
    public void onStopListening() {
        OutdoorState.get(this).removeListener(mOnChanged);
    }

    @Override
    public void onClick() {
        OutdoorController.setEnabled(this, !OutdoorState.get(this).isEnabled());
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(OutdoorState.get(this).isEnabled() ? Tile.STATE_ACTIVE
                : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
