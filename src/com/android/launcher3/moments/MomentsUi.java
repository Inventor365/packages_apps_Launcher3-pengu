package com.android.launcher3.moments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.Configuration;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.ZenPolicy;

import com.android.launcher3.R;

final class MomentsUi {

    private MomentsUi() {}

    private static final int[] ICONS = {
            R.drawable.ic_moment_sparkle,
            R.drawable.ic_moment_fp_deep_focus,
            R.drawable.ic_moment_fp_quality_time,
            R.drawable.ic_moment_fp_journey,
            R.drawable.ic_moment_fp_recharge,
            R.drawable.ic_moment_fp_extra1,
            R.drawable.ic_moment_fp_extra2,
            R.drawable.ic_moment_fp_extra3,
            R.drawable.ic_moment_fp_extra4,
            R.drawable.ic_moment_fp_extra5,
            R.drawable.ic_moment_fp_extra6,
    };

    static int iconRes(int icon) {
        return ICONS[icon >= 0 && icon < ICONS.length ? icon : Moment.ICON_SPARKLE];
    }

    // Fairphone's LauncherColors as {right, left}, in Moment.COLORS_* order.
    private static final int[][] COLORS = {
            {0xB2FFBA63, 0xB2C3D1D0},
            {0xB2FACAC9, 0xB2EBD1F8},
            {0xB282C9F1, 0xB2CBCEEA},
            {0xB2F7CAC9, 0xB2E5D1F8},
            {0xB2D8FF4F, 0xB2BBD9D6},
            {0xB2C0AFFF, 0xB2B0CCD8},
            {0xFF0B1410, 0xFF14241E},
            {0xFF9DA3AA, 0xFFE0DEDC},
            {0xFF060505, 0xFF191715},
            {0xFF192132, 0xFF33427C},
    };

    static int rightColor(int colors) {
        return COLORS[Math.floorMod(colors, COLORS.length)][0];
    }

    static int leftColor(int colors) {
        return COLORS[Math.floorMod(colors, COLORS.length)][1];
    }

    /** Green, White, Black and Blue are flat gradients rather than blobs. */
    static boolean isStatic(int colors) {
        return Math.floorMod(colors, COLORS.length) >= 6;
    }

    static boolean isNight(Context context, int uiMode) {
        if (uiMode == Moment.UI_DARK) {
            return true;
        }
        if (uiMode == Moment.UI_LIGHT) {
            return false;
        }
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** A context whose resources follow the Moment's light/dark choice. */
    static Context themed(Context context, int uiMode) {
        if (uiMode == Moment.UI_SYSTEM) {
            return context;
        }
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (uiMode == Moment.UI_DARK ? Configuration.UI_MODE_NIGHT_YES
                        : Configuration.UI_MODE_NIGHT_NO);
        return context.createConfigurationContext(config);
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

    static final int[] SOUND_TITLES = {R.string.moments_sound_device,
            R.string.moments_sound_loud, R.string.moments_sound_vibrate,
            R.string.moments_sound_silent};
    static final int[] SOUND_SUMMARIES = {R.string.moments_sound_device_summary,
            R.string.moments_sound_loud_summary, R.string.moments_sound_vibrate_summary,
            R.string.moments_sound_silent_summary};
    static final int[] UI_MODE_TITLES = {R.string.moments_ui_system,
            R.string.moments_ui_light, R.string.moments_ui_dark};

    // App entries are flattened ComponentNames, with "#<user serial>" for other profiles.

    static String key(Context context, LauncherActivityInfo info) {
        String component = info.getComponentName().flattenToString();
        if (info.getUser().equals(Process.myUserHandle())) {
            return component;
        }
        return component + "#" + context.getSystemService(UserManager.class)
                .getSerialNumberForUser(info.getUser());
    }

    static ComponentName component(String app) {
        int split = app.indexOf('#');
        return ComponentName.unflattenFromString(split < 0 ? app : app.substring(0, split));
    }

    /** Null when that profile is gone. */
    static UserHandle user(Context context, String app) {
        int split = app.indexOf('#');
        if (split < 0) {
            return Process.myUserHandle();
        }
        try {
            return context.getSystemService(UserManager.class).getUserForSerialNumber(
                    Long.parseLong(app.substring(split + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isWork(String app) {
        return app.indexOf('#') >= 0;
    }

    /** Null when the app is gone. */
    static LauncherActivityInfo resolve(Context context, String app) {
        ComponentName component = component(app);
        UserHandle user = user(context, app);
        if (component == null || user == null) {
            return null;
        }
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        return launcherApps.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component), user);
    }
}
