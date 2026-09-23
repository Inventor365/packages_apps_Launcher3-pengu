package com.android.launcher3.moments;

import android.service.notification.ZenPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Moment {

    public static final int MAX_APPS = 5;

    public static final int ICON_SPARKLE = 0;
    public static final int ICON_FOCUS = 1;
    public static final int ICON_HEART = 2;
    public static final int ICON_JOURNEY = 3;
    public static final int ICON_MOON = 4;
    public static final int ICON_COUNT = 5;
    public static final int PALETTE_COUNT = 6;

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
    public int palette;
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
                .put("palette", palette)
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

    static int presetPalette(String id) {
        switch (id) {
            case "deep_focus":
                return 5;
            case "quality_time":
                return 3;
            case "journey":
                return 1;
            case "recharge":
                return 4;
            default:
                return 0;
        }
    }

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
        m.palette = json.has("palette") ? json.getInt("palette") : presetPalette(m.id);
        m.scheduleEnabled = json.optBoolean("scheduleEnabled");
        m.scheduleDays = json.optInt("scheduleDays", m.scheduleDays);
        m.scheduleStart = json.optInt("scheduleStart", m.scheduleStart);
        m.scheduleEnd = json.optInt("scheduleEnd", m.scheduleEnd);
        m.carTrigger = json.optBoolean("carTrigger");
        m.calls = json.optInt("calls", ZenPolicy.PEOPLE_TYPE_CONTACTS);
        m.messages = json.optInt("messages", ZenPolicy.PEOPLE_TYPE_CONTACTS);
        m.appNotifications = json.optBoolean("appNotifications", true);
        m.blockOtherApps = json.optBoolean("block", true);
        m.grayscale = json.optBoolean("grayscale");
        m.dimWallpaper = json.optBoolean("dimWallpaper");
        m.zenRuleId = json.isNull("zenRuleId") ? null : json.optString("zenRuleId", null);
        return m;
    }
}
