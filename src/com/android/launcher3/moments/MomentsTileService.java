package com.android.launcher3.moments;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.android.launcher3.R;

public class MomentsTileService extends TileService {

    private final Runnable mOnChanged = this::updateTile;

    @Override
    public void onStartListening() {
        MomentsStore.get(this).addListener(mOnChanged);
        updateTile();
    }

    @Override
    public void onStopListening() {
        MomentsStore.get(this).removeListener(mOnChanged);
    }

    @Override
    public void onClick() {
        MomentsStore store = MomentsStore.get(this);
        if (store.getActive() == null) {
            Moment moment = store.getDefault();
            if (moment == null) {
                openPicker();
                return;
            }
            MomentsController.enter(this, moment);
        }
        // Leaving is done from the Moment's home screen, so the tile only ever takes you there.
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, home,
                PendingIntent.FLAG_IMMUTABLE));
    }

    private void openPicker() {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0,
                new Intent(this, MomentsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE));
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        Moment active = MomentsStore.get(this).getActive();
        Moment shown = active != null ? active : MomentsStore.get(this).getDefault();
        tile.setState(active != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.moments_title));
        tile.setSubtitle(shown == null ? null : shown.name);
        tile.setIcon(Icon.createWithResource(this,
                MomentsUi.iconRes(shown == null ? Moment.ICON_SPARKLE : shown.icon)));
        tile.updateTile();
    }
}
