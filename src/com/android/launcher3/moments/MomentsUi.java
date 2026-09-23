package com.android.launcher3.moments;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Process;
import android.service.notification.ZenPolicy;

import com.android.launcher3.R;

final class MomentsUi {

    private MomentsUi() {}

    static int iconRes(int icon) {
        switch (icon) {
            case Moment.ICON_FOCUS:
                return R.drawable.ic_moment_focus;
            case Moment.ICON_HEART:
                return R.drawable.ic_moment_heart;
            case Moment.ICON_JOURNEY:
                return R.drawable.ic_moment_journey;
            case Moment.ICON_MOON:
                return R.drawable.ic_moment_moon;
            default:
                return R.drawable.ic_moment_sparkle;
        }
    }

    // {night top, night bottom, day top, day bottom} per palette.
    private static final int[][] PALETTES = {
            {0xFF000000, 0xFF7A5A33, 0xFFE4E6EA, 0xFFF2C99A},
            {0xFF000000, 0xFF1F4E6B, 0xFFE3E8EE, 0xFFA9CBE3},
            {0xFF000000, 0xFF2E5A3C, 0xFFE5EAE3, 0xFFB5D6B0},
            {0xFF000000, 0xFF6B2E45, 0xFFEEE4E8, 0xFFE8B4C4},
            {0xFF000000, 0xFF4B3A73, 0xFFE8E4EF, 0xFFC6B8E6},
            {0xFF000000, 0xFF3A3A3A, 0xFFEDEDED, 0xFFC8C8C8},
    };

    static GradientDrawable background(Context context, int palette) {
        int[] colors = PALETTES[Math.floorMod(palette, PALETTES.length)];
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                night ? new int[]{colors[0], colors[1]} : new int[]{colors[2], colors[3]});
    }

    static int swatch(int palette) {
        return PALETTES[Math.floorMod(palette, PALETTES.length)][1];
    }

    static final int[] PEOPLE_TYPES = {ZenPolicy.PEOPLE_TYPE_ANYONE,
            ZenPolicy.PEOPLE_TYPE_CONTACTS, ZenPolicy.PEOPLE_TYPE_STARRED,
            ZenPolicy.PEOPLE_TYPE_NONE};

    static String peopleLabel(Context context, int type) {
        switch (type) {
            case ZenPolicy.PEOPLE_TYPE_ANYONE:
                return context.getString(R.string.moments_people_anyone);
            case ZenPolicy.PEOPLE_TYPE_STARRED:
                return context.getString(R.string.moments_people_starred);
            case ZenPolicy.PEOPLE_TYPE_NONE:
                return context.getString(R.string.moments_people_none);
            default:
                return context.getString(R.string.moments_people_contacts);
        }
    }

    /** Null when the app is gone. */
    static LauncherActivityInfo resolve(Context context, String app) {
        ComponentName component = ComponentName.unflattenFromString(app);
        if (component == null) {
            return null;
        }
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        return launcherApps.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component), Process.myUserHandle());
    }
}
