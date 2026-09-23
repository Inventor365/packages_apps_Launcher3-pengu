package com.android.launcher3.outdoor;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class OutdoorState {

    public static final int MAX_APPS = 4;

    static final String PREFS_NAME = "outdoor_mode";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_APPS = "apps";
    static final String KEY_HIDE_IN_SCREENSHOTS = "hide_in_screenshots";
    static final String KEY_ENHANCED_CALL = "enhanced_call";
    static final String KEY_EXTEND_TIMEOUT = "extend_timeout";
    static final String KEY_AMPLIFIER = "amplifier";
    // What Outdoor mode overwrote, so it can be put back when it ends.
    static final String KEY_SAVED_TIMEOUT = "saved_timeout";
    static final String KEY_SAVED_RING = "saved_ring";
    static final String KEY_SAVED_NOTIFICATION = "saved_notification";

    // Apps people most often need outside: chat and UPI payments.
    private static final String[] DEFAULT_APPS = {"com.whatsapp",
            "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm"};

    private static OutdoorState sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Runnable> mListeners = new CopyOnWriteArrayList<>();
    // SharedPreferences only holds listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (prefs, key) -> mMain.post(() -> mListeners.forEach(Runnable::run));

    private OutdoorState(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
    }

    public static synchronized OutdoorState get(Context context) {
        if (sInstance == null) {
            sInstance = new OutdoorState(context);
        }
        return sInstance;
    }

    SharedPreferences prefs() {
        return mPrefs;
    }

    public void addListener(Runnable listener) {
        mListeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        mListeners.remove(listener);
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, false);
    }

    boolean hideInScreenshots() {
        return mPrefs.getBoolean(KEY_HIDE_IN_SCREENSHOTS, true);
    }

    boolean enhancedCall() {
        return mPrefs.getBoolean(KEY_ENHANCED_CALL, false);
    }

    boolean extendTimeout() {
        return mPrefs.getBoolean(KEY_EXTEND_TIMEOUT, false);
    }

    boolean amplifier() {
        return mPrefs.getBoolean(KEY_AMPLIFIER, false);
    }

    /** Flattened {@link ComponentName}s shown on the dock. */
    public List<String> getApps() {
        String saved = mPrefs.getString(KEY_APPS, null);
        if (saved != null) {
            return saved.isEmpty() ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(saved.split(";")));
        }
        List<String> apps = new ArrayList<>();
        LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);
        for (String pkg : DEFAULT_APPS) {
            List<LauncherActivityInfo> found =
                    launcherApps.getActivityList(pkg, Process.myUserHandle());
            if (!found.isEmpty() && apps.size() < 2) {
                apps.add(found.get(0).getComponentName().flattenToString());
            }
        }
        return apps;
    }

    public void setApps(List<String> apps) {
        mPrefs.edit().putString(KEY_APPS, TextUtils.join(";", apps)).apply();
    }
}
