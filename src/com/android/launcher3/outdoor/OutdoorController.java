package com.android.launcher3.outdoor;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import com.android.launcher3.R;

public final class OutdoorController {

    private static final String TAG = "OutdoorMode";
    private static final int EXTENDED_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int UNTOUCHED = 0;

    private static BroadcastReceiver sBatteryLow;

    private OutdoorController() {}

    public static void setEnabled(Context context, boolean enabled) {
        OutdoorState.get(context).prefs().edit()
                .putBoolean(OutdoorState.KEY_ENABLED, enabled).commit();
        apply(context);
    }

    /** Brings the system in line with the current settings. Safe to call repeatedly. */
    public static void apply(Context context) {
        Context app = context.getApplicationContext();
        OutdoorState state = OutdoorState.get(app);
        boolean on = state.isEnabled();
        applyTimeout(app, on && state.extendTimeout());
        applyVolumes(app, on && state.amplifier());
        app.getPackageManager().setComponentEnabledSetting(
                new ComponentName(app, OutdoorInCallService.class),
                on && state.enhancedCall() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        OutdoorDock.get(app).setEnabled(on);
        watchBattery(app, on);
        OutdoorWidgetProvider.updateAll(app);
        TileService.requestListeningState(app, new ComponentName(app, OutdoorTileService.class));
    }

    private static void applyTimeout(Context context, boolean extend) {
        SharedPreferences prefs = OutdoorState.get(context).prefs();
        boolean applied = prefs.contains(OutdoorState.KEY_SAVED_TIMEOUT);
        try {
            int current = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, EXTENDED_TIMEOUT_MS);
            if (extend && !applied) {
                boolean raise = current < EXTENDED_TIMEOUT_MS;
                prefs.edit().putInt(OutdoorState.KEY_SAVED_TIMEOUT,
                        raise ? current : UNTOUCHED).commit();
                if (raise) {
                    Settings.System.putInt(context.getContentResolver(),
                            Settings.System.SCREEN_OFF_TIMEOUT, EXTENDED_TIMEOUT_MS);
                }
            } else if (!extend && applied) {
                int saved = prefs.getInt(OutdoorState.KEY_SAVED_TIMEOUT, UNTOUCHED);
                // Leave it alone if the user picked a new timeout meanwhile.
                if (saved != UNTOUCHED && current == EXTENDED_TIMEOUT_MS) {
                    Settings.System.putInt(context.getContentResolver(),
                            Settings.System.SCREEN_OFF_TIMEOUT, saved);
                }
                prefs.edit().remove(OutdoorState.KEY_SAVED_TIMEOUT).commit();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not change the screen timeout", e);
        }
    }

    private static void applyVolumes(Context context, boolean amplify) {
        SharedPreferences prefs = OutdoorState.get(context).prefs();
        AudioManager audio = context.getSystemService(AudioManager.class);
        boolean applied = prefs.contains(OutdoorState.KEY_SAVED_RING);
        try {
            if (amplify && !applied) {
                // Raising the ring volume would also take the phone out of silent or vibrate.
                if (audio.getRingerModeInternal() != AudioManager.RINGER_MODE_NORMAL) {
                    return;
                }
                prefs.edit()
                        .putInt(OutdoorState.KEY_SAVED_RING,
                                audio.getStreamVolume(AudioManager.STREAM_RING))
                        .putInt(OutdoorState.KEY_SAVED_NOTIFICATION,
                                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                        .commit();
                maximize(audio, AudioManager.STREAM_RING);
                maximize(audio, AudioManager.STREAM_NOTIFICATION);
            } else if (!amplify && applied) {
                restore(audio, AudioManager.STREAM_RING,
                        prefs.getInt(OutdoorState.KEY_SAVED_RING, -1));
                restore(audio, AudioManager.STREAM_NOTIFICATION,
                        prefs.getInt(OutdoorState.KEY_SAVED_NOTIFICATION, -1));
                prefs.edit().remove(OutdoorState.KEY_SAVED_RING)
                        .remove(OutdoorState.KEY_SAVED_NOTIFICATION).commit();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not change alert volumes", e);
        }
    }

    private static void maximize(AudioManager audio, int stream) {
        audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0);
    }

    private static void restore(AudioManager audio, int stream, int saved) {
        if (saved >= 0 && audio.getStreamVolume(stream) == audio.getStreamMaxVolume(stream)) {
            audio.setStreamVolume(stream, saved, 0);
        }
    }

    private static synchronized void watchBattery(Context context, boolean on) {
        if (on && sBatteryLow == null) {
            sBatteryLow = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    setEnabled(c, false);
                    Toast.makeText(c, R.string.outdoor_battery_off, Toast.LENGTH_LONG).show();
                }
            };
            context.registerReceiver(sBatteryLow, new IntentFilter(Intent.ACTION_BATTERY_LOW),
                    Context.RECEIVER_NOT_EXPORTED);
        } else if (!on && sBatteryLow != null) {
            context.unregisterReceiver(sBatteryLow);
            sBatteryLow = null;
        }
    }
}
