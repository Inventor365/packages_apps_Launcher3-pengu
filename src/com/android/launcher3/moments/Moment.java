package com.android.launcher3.moments;

import android.service.notification.ZenPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Moment {

    public static final int MAX_APPS = 5;
    public static final int MAX_MOMENTS = 6;
    public static final int MAX_NAME_LENGTH = 15;

    public static final int ICON_SPARKLE = 0;
    public static final int ICON_FOCUS = 1;
    public static final int ICON_HEART = 2;
    public static final int ICON_JOURNEY = 3;
    public static final int ICON_MOON = 4;
    /** ICON_EXTRA + 0..5 are Fairphone's Extra1..Extra6. */
    public static final int ICON_EXTRA = 5;
    public static final int ICON_COUNT = 11;

    public static final int COLORS_DEFAULT = 0;
    public static final int COLORS_CUSTOM = 1;
    public static final int COLORS_DEEP_FOCUS = 2;
    public static final int COLORS_JOURNEY = 3;
    public static final int COLORS_RECHARGE = 4;
    public static final int COLORS_QUALITY_TIME = 5;
    public static final int COLORS_COUNT = 10;

    public static final int SOUND_DEVICE = 0;
    public static final int SOUND_LOUD = 1;
    public static final int SOUND_VIBRATE = 2;
    public static final int SOUND_SILENT = 3;

    public static final int UI_SYSTEM = 0;
    public static final int UI_LIGHT = 1;
    public static final int UI_DARK = 2;

    public String id;
    public String name;
    public int icon;
    /** Flattened {@link android.content.ComponentName}s of the current user. */
    public final List<String> apps = new ArrayList<>();
    /** Kept unpaused but off the Moment's home, e.g. music or navigation. */
    public final List<String> background = new ArrayList<>();
    /** A {@link ZenPolicy} PEOPLE_TYPE_* value. */
    public int calls = ZenPolicy.PEOPLE_TYPE_CONTACTS;
    public int messages = ZenPolicy.PEOPLE_TYPE_CONTACTS;
    public boolean appNotifications = true;
    public boolean blockOtherApps = true;
    public boolean grayscale;
    public boolean dimWallpaper;
    public int colors;
    public boolean repeatCallers = true;
    public int sound = SOUND_DEVICE;
    public int uiMode = UI_SYSTEM;
    public boolean blueLight;
    public boolean calmLockWallpaper;
    public boolean batterySaver;
    public boolean extraDim;
    public boolean aodOff;
    public boolean airplane;
    public boolean scheduleEnabled;
    /** Bit n set means {@link java.util.Calendar} day n + 1. */
    public int scheduleDays = 0b0111110;
    /** Minutes after midnight. An end at or before the start runs into the next day. */
    public int scheduleStart = 22 * 60;
    public int scheduleEnd = 7 * 60;
    public boolean carTrigger;
    public String zenRuleId;

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("icon", icon)
                .put("apps", new JSONArray(apps))
                .put("background", new JSONArray(background))
                .put("colors", colors)
                .put("repeatCallers", repeatCallers)
                .put("sound", sound)
                .put("uiMode", uiMode)
                .put("blueLight", blueLight)
                .put("calmLock", calmLockWallpaper)
                .put("batterySaver", batterySaver)
                .put("extraDim", extraDim)
                .put("aodOff", aodOff)
                .put("airplane", airplane)
                .put("scheduleEnabled", scheduleEnabled)
                .put("scheduleDays", scheduleDays)
                .put("scheduleStart", scheduleStart)
                .put("scheduleEnd", scheduleEnd)
                .put("carTrigger", carTrigger)
                .put("calls", calls)
                .put("messages", messages)
                .put("appNotifications", appNotifications)
                .put("block", blockOtherApps)
                .put("grayscale", grayscale)
                .put("dimWallpaper", dimWallpaper)
                .put("zenRuleId", zenRuleId == null ? JSONObject.NULL : zenRuleId);
    }

    static int presetColors(String id) {
        switch (id) {
            case "deep_focus":
                return COLORS_DEEP_FOCUS;
            case "quality_time":
                return COLORS_QUALITY_TIME;
            case "journey":
                return COLORS_JOURNEY;
            case "recharge":
                return COLORS_RECHARGE;
            case "essentials":
                return COLORS_DEFAULT;
            default:
                return -1;
        }
    }

    // The six gradients used before the Fairphone colour pairs, by their closest pair.
    private static final int[] OLD_PALETTES = {COLORS_DEFAULT, COLORS_DEEP_FOCUS,
            COLORS_RECHARGE, COLORS_JOURNEY, COLORS_QUALITY_TIME, 7};

    static Moment fromJson(JSONObject json) throws JSONException {
        Moment m = new Moment();
        m.id = json.getString("id");
        m.name = json.getString("name");
        m.icon = json.optInt("icon", ICON_SPARKLE);
        JSONArray apps = json.optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length() && i < MAX_APPS; i++) {
            m.apps.add(apps.getString(i));
        }
        JSONArray background = json.optJSONArray("background");
        for (int i = 0; background != null && i < background.length() && i < MAX_APPS; i++) {
            m.background.add(background.getString(i));
        }
        int preset = presetColors(m.id);
        if (json.has("colors")) {
            m.colors = Math.floorMod(json.getInt("colors"), COLORS_COUNT);
        } else if (preset >= 0) {
            m.colors = preset;
        } else {
            m.colors = OLD_PALETTES[Math.floorMod(json.optInt("palette"), OLD_PALETTES.length)];
        }
        m.sound = json.optInt("sound", SOUND_DEVICE);
        m.uiMode = json.optInt("uiMode", UI_SYSTEM);
        m.blueLight = json.optBoolean("blueLight");
        m.calmLockWallpaper = json.optBoolean("calmLock");
        m.batterySaver = json.optBoolean("batterySaver");
        m.extraDim = json.optBoolean("extraDim");
        m.aodOff = json.optBoolean("aodOff");
        m.airplane = json.optBoolean("airplane");
        m.scheduleEnabled = json.optBoolean("scheduleEnabled");
        m.scheduleDays = json.optInt("scheduleDays", m.scheduleDays);
        m.scheduleStart = json.optInt("scheduleStart", m.scheduleStart);
        m.scheduleEnd = json.optInt("scheduleEnd", m.scheduleEnd);
        m.carTrigger = json.optBoolean("carTrigger");
        m.calls = json.optInt("calls", ZenPolicy.PEOPLE_TYPE_CONTACTS);
        m.messages = json.optInt("messages", ZenPolicy.PEOPLE_TYPE_CONTACTS);
        m.repeatCallers = json.optBoolean("repeatCallers",
                m.calls != ZenPolicy.PEOPLE_TYPE_NONE);
        m.appNotifications = json.optBoolean("appNotifications", true);
        m.blockOtherApps = json.optBoolean("block", true);
        m.grayscale = json.optBoolean("grayscale");
        m.dimWallpaper = json.optBoolean("dimWallpaper");
        m.zenRuleId = json.isNull("zenRuleId") ? null : json.optString("zenRuleId", null);
        return m;
    }
}
