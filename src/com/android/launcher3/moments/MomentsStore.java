package com.android.launcher3.moments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.service.quicksettings.TileService;
import android.service.notification.ZenPolicy;
import android.telecom.TelecomManager;

import com.android.launcher3.R;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MomentsStore {

    private static final String PREFS = "moments";
    private static final String KEY_MOMENTS = "moments";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_LAST = "last";
    private static final String KEY_SUSPENDED = "suspended";
    private static final String KEY_BYPASSED = "bypassed_channels";

    private static MomentsStore sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Runnable> mListeners = new CopyOnWriteArrayList<>();

    private MomentsStore(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized MomentsStore get(Context context) {
        if (sInstance == null) {
            sInstance = new MomentsStore(context);
        }
        return sInstance;
    }

    public void addListener(Runnable listener) {
        mListeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        mListeners.remove(listener);
    }

    private void notifyChanged() {
        mMain.post(() -> mListeners.forEach(Runnable::run));
    }

    public synchronized List<Moment> getMoments() {
        String json = mPrefs.getString(KEY_MOMENTS, null);
        List<Moment> moments = new ArrayList<>();
        if (json == null) {
            moments.addAll(presets());
            save(moments);
            return moments;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                moments.add(Moment.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException e) {
            moments.addAll(presets());
        }
        return moments;
    }

    public Moment getMoment(String id) {
        for (Moment moment : getMoments()) {
            if (moment.id.equals(id)) {
                return moment;
            }
        }
        return null;
    }

    public synchronized void putMoment(Moment moment) {
        List<Moment> moments = getMoments();
        boolean replaced = false;
        for (int i = 0; i < moments.size(); i++) {
            if (moments.get(i).id.equals(moment.id)) {
                // An editor's copy can predate the mode being created for it.
                if (moment.zenRuleId == null) {
                    moment.zenRuleId = moments.get(i).zenRuleId;
                }
                moments.set(i, moment);
                replaced = true;
            }
        }
        if (!replaced) {
            moments.add(moment);
        }
        save(moments);
        notifyChanged();
    }

    public synchronized void removeMoment(String id) {
        List<Moment> moments = getMoments();
        moments.removeIf(m -> m.id.equals(id));
        save(moments);
        if (id.equals(getLastId())) {
            mPrefs.edit().remove(KEY_LAST).apply();
        }
        notifyChanged();
    }

    private void save(List<Moment> moments) {
        JSONArray array = new JSONArray();
        try {
            for (Moment moment : moments) {
                array.put(moment.toJson());
            }
        } catch (JSONException e) {
            return;
        }
        mPrefs.edit().putString(KEY_MOMENTS, array.toString()).apply();
    }

    public Moment newMoment() {
        Moment moment = new Moment();
        moment.id = UUID.randomUUID().toString();
        moment.name = mContext.getString(R.string.moments_new_name);
        return moment;
    }

    public String getActiveId() {
        return mPrefs.getString(KEY_ACTIVE, null);
    }

    public Moment getActive() {
        String id = getActiveId();
        return id == null ? null : getMoment(id);
    }

    public String getLastId() {
        return mPrefs.getString(KEY_LAST, null);
    }

    /** The Moment the tile turns on: the last one used, else the first. */
    public Moment getDefault() {
        Moment last = getLastId() == null ? null : getMoment(getLastId());
        if (last != null) {
            return last;
        }
        List<Moment> moments = getMoments();
        return moments.isEmpty() ? null : moments.get(0);
    }

    void setActive(String id) {
        SharedPreferences.Editor editor = mPrefs.edit().putString(KEY_ACTIVE, id);
        if (id != null) {
            editor.putString(KEY_LAST, id);
        }
        editor.commit();
        notifyChanged();
        // SystemUI only binds a few custom tiles at a time; an active tile has to ask.
        TileService.requestListeningState(mContext,
                new ComponentName(mContext, MomentsTileService.class));
    }

    Set<String> getSuspended() {
        return new HashSet<>(mPrefs.getStringSet(KEY_SUSPENDED, Set.of()));
    }

    void setSuspended(Set<String> packages) {
        mPrefs.edit().putStringSet(KEY_SUSPENDED, new HashSet<>(packages)).commit();
    }

    /** Entries are "package/uid/channelId". */
    Set<String> getBypassedChannels() {
        return new HashSet<>(mPrefs.getStringSet(KEY_BYPASSED, Set.of()));
    }

    void setBypassedChannels(Set<String> channels) {
        mPrefs.edit().putStringSet(KEY_BYPASSED, new HashSet<>(channels)).commit();
    }

    private List<Moment> presets() {
        String phone = launchable(mContext.getSystemService(TelecomManager.class)
                .getDefaultDialerPackage());
        String messages = launchable(Telephony.Sms.getDefaultSmsPackage(mContext));
        String camera = resolve(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        String maps = resolve(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")));
        String music = resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                Intent.CATEGORY_APP_MUSIC));
        String clock = resolve(new Intent(AlarmClock.ACTION_SHOW_ALARMS));

        List<Moment> presets = new ArrayList<>();
        presets.add(preset("essentials", R.string.moments_preset_essentials, Moment.ICON_SPARKLE,
                ZenPolicy.PEOPLE_TYPE_CONTACTS, true, false, false,
                phone, messages, camera, maps, clock));
        presets.add(preset("deep_focus", R.string.moments_preset_deep_focus, Moment.ICON_FOCUS,
                ZenPolicy.PEOPLE_TYPE_STARRED, false, true, false, phone));
        presets.add(preset("quality_time", R.string.moments_preset_quality_time,
                Moment.ICON_HEART, ZenPolicy.PEOPLE_TYPE_CONTACTS, false, false, false,
                phone, camera));
        presets.add(preset("journey", R.string.moments_preset_journey, Moment.ICON_JOURNEY,
                ZenPolicy.PEOPLE_TYPE_CONTACTS, true, false, false,
                maps, music, messages, phone));
        presets.add(preset("recharge", R.string.moments_preset_recharge, Moment.ICON_MOON,
                ZenPolicy.PEOPLE_TYPE_STARRED, false, true, true, phone, clock));
        return presets;
    }

    private Moment preset(String id, int name, int icon, int people, boolean appNotifications,
            boolean grayscale, boolean dimWallpaper, String... apps) {
        Moment moment = new Moment();
        moment.id = id;
        moment.name = mContext.getString(name);
        moment.icon = icon;
        moment.calls = people;
        moment.messages = people;
        moment.appNotifications = appNotifications;
        moment.grayscale = grayscale;
        moment.dimWallpaper = dimWallpaper;
        for (String app : apps) {
            if (app != null && !moment.apps.contains(app) && moment.apps.size() < Moment.MAX_APPS) {
                moment.apps.add(app);
            }
        }
        return moment;
    }

    private String resolve(Intent intent) {
        ResolveInfo info = mContext.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null
                || "android".equals(info.activityInfo.packageName)) {
            return null;
        }
        return launchable(info.activityInfo.packageName);
    }

    private String launchable(String pkg) {
        if (pkg == null) {
            return null;
        }
        LauncherApps apps = mContext.getSystemService(LauncherApps.class);
        List<LauncherActivityInfo> activities =
                apps.getActivityList(pkg, Process.myUserHandle());
        if (activities.isEmpty()) {
            return null;
        }
        ComponentName component = activities.get(0).getComponentName();
        return component.flattenToString();
    }
}
