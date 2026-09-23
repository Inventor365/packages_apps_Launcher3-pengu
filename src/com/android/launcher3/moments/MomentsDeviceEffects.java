package com.android.launcher3.moments;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.ColorDisplayManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Device settings a Moment overrides. The value each one had before is saved the first time
 * it is overridden and written back once no Moment wants it, so switching between Moments
 * that share a setting doesn't flip it off and on.
 */
final class MomentsDeviceEffects {

    private static final String TAG = "MomentsEffects";
    private static final String PREFS = "moments_saved_settings";
    private static final String KEY_RINGER = "ringer";
    private static final String KEY_RING_VOLUME = "ring_volume";
    private static final String KEY_NOTIFICATION_VOLUME = "notification_volume";
    private static final String KEY_NIGHT_DISPLAY = "night_display";
    private static final String KEY_EXTRA_DIM = "extra_dim";
    private static final String KEY_POWER_SAVE = "power_save";
    private static final String KEY_AIRPLANE = "airplane";
    private static final String KEY_LOCK_WALLPAPER = "lock_wallpaper";
    private static final String KEY_LOCK_COLORS = "lock_colors";
    private static final String LOCK_SHARED = "shared";
    private static final String LOCK_FILE = "file";
    private static final String LOCK_LIVE = "live:";
    private static final String LOCK_BACKUP = "moments_lock_wallpaper";

    private MomentsDeviceEffects() {}

    /** Applies {@code moment}'s overrides, or restores everything when it is null. */
    static void apply(Context context, Moment moment) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AudioManager audio = context.getSystemService(AudioManager.class);
        ColorDisplayManager color = context.getSystemService(ColorDisplayManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);

        int sound = moment == null ? Moment.SOUND_DEVICE : moment.sound;
        int ringer = sound == Moment.SOUND_LOUD ? AudioManager.RINGER_MODE_NORMAL
                : sound == Moment.SOUND_VIBRATE ? AudioManager.RINGER_MODE_VIBRATE
                        : AudioManager.RINGER_MODE_SILENT;
        // The internal ringer mode, as the volume panel sets it: the external one would
        // turn Do Not Disturb, and so the Moment's mode, off.
        override(prefs, KEY_RINGER, sound != Moment.SOUND_DEVICE, ringer,
                audio::getRingerModeInternal, audio::setRingerModeInternal);
        override(prefs, KEY_RING_VOLUME, sound == Moment.SOUND_LOUD,
                audio.getStreamMaxVolume(AudioManager.STREAM_RING),
                () -> audio.getStreamVolume(AudioManager.STREAM_RING),
                v -> audio.setStreamVolume(AudioManager.STREAM_RING, v, 0));
        override(prefs, KEY_NOTIFICATION_VOLUME, sound == Moment.SOUND_LOUD,
                audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
                () -> audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                v -> audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, v, 0));

        override(prefs, KEY_NIGHT_DISPLAY, moment != null && moment.blueLight, 1,
                () -> color.isNightDisplayActivated() ? 1 : 0,
                v -> color.setNightDisplayActivated(v == 1));
        override(prefs, KEY_EXTRA_DIM, moment != null && moment.extraDim, 1,
                () -> color.isReduceBrightColorsActivated() ? 1 : 0,
                v -> color.setReduceBrightColorsActivated(v == 1));
        override(prefs, KEY_POWER_SAVE, moment != null && moment.batterySaver, 1,
                () -> power.isPowerSaveMode() ? 1 : 0,
                v -> power.setPowerSaveModeEnabled(v == 1));
        override(prefs, KEY_AIRPLANE, moment != null && moment.airplane, 1,
                () -> Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0),
                v -> connectivity.setAirplaneMode(v == 1));

        lockWallpaper(context, prefs, moment != null && moment.calmLockWallpaper ? moment : null);
    }

    private static void override(SharedPreferences prefs, String key, boolean wanted, int value,
            IntSupplier read, IntConsumer write) {
        try {
            if (wanted) {
                if (!prefs.contains(key)) {
                    prefs.edit().putInt(key, read.getAsInt()).commit();
                }
                write.accept(value);
            } else if (prefs.contains(key)) {
                write.accept(prefs.getInt(key, value));
                prefs.edit().remove(key).commit();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not change " + key, e);
        }
    }

    /** Puts the Moment's own background on the lock screen, calmer than a photo. */
    private static void lockWallpaper(Context context, SharedPreferences prefs, Moment moment) {
        WallpaperManager wm = context.getSystemService(WallpaperManager.class);
        String saved = prefs.getString(KEY_LOCK_WALLPAPER, null);
        File backup = new File(context.getFilesDir(), LOCK_BACKUP);
        try {
            if (moment == null) {
                if (saved == null) {
                    return;
                }
                if (saved.startsWith(LOCK_LIVE)) {
                    wm.setWallpaperComponentWithFlags(ComponentName.unflattenFromString(
                            saved.substring(LOCK_LIVE.length())), WallpaperManager.FLAG_LOCK);
                } else if (saved.equals(LOCK_FILE) && backup.exists()) {
                    try (InputStream in = new FileInputStream(backup)) {
                        wm.setStream(in, null, true, WallpaperManager.FLAG_LOCK);
                    }
                } else {
                    wm.clear(WallpaperManager.FLAG_LOCK);
                }
                backup.delete();
                prefs.edit().remove(KEY_LOCK_WALLPAPER).remove(KEY_LOCK_COLORS).commit();
                return;
            }

            boolean night = MomentsUi.isNight(context, moment.uiMode);
            String colors = moment.colors + (night ? "n" : "d");
            if (colors.equals(prefs.getString(KEY_LOCK_COLORS, null))) {
                return;
            }
            if (saved == null) {
                prefs.edit().putString(KEY_LOCK_WALLPAPER, saveLockWallpaper(wm, backup))
                        .commit();
            }
            Rect bounds = context.getSystemService(WindowManager.class)
                    .getMaximumWindowMetrics().getBounds();
            Bitmap bitmap = MomentsBackgroundView.render(context, moment.colors, night,
                    Math.min(bounds.width(), bounds.height()),
                    Math.max(bounds.width(), bounds.height()));
            if (bitmap != null) {
                wm.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK);
                prefs.edit().putString(KEY_LOCK_COLORS, colors).commit();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not change the lock screen wallpaper", e);
        }
    }

    private static String saveLockWallpaper(WallpaperManager wm, File backup) throws IOException {
        if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0) {
            return LOCK_SHARED;
        }
        WallpaperInfo live = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
        if (live != null) {
            return LOCK_LIVE + live.getComponent().flattenToString();
        }
        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)) {
            if (pfd == null) {
                return LOCK_SHARED;
            }
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
                    OutputStream out = new FileOutputStream(backup)) {
                in.transferTo(out);
            }
        }
        return LOCK_FILE;
    }
}
