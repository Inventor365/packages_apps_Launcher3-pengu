package com.android.launcher3.moments;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.Intent;
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
