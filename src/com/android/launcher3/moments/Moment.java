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

    public String id;
    public String name;
    public int icon;
    /** Flattened {@link android.content.ComponentName}s of the current user. */
    public final List<String> apps = new ArrayList<>();
    /** A {@link ZenPolicy} PEOPLE_TYPE_* value. */
    public int calls = ZenPolicy.PEOPLE_TYPE_CONTACTS;
    public int messages = ZenPolicy.PEOPLE_TYPE_CONTACTS;
    public boolean appNotifications = true;
    public boolean blockOtherApps = true;
    public boolean grayscale;
    public boolean dimWallpaper;
    public String zenRuleId;

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("icon", icon)
                .put("apps", new JSONArray(apps))
                .put("calls", calls)
                .put("messages", messages)
                .put("appNotifications", appNotifications)
                .put("block", blockOtherApps)
                .put("grayscale", grayscale)
                .put("dimWallpaper", dimWallpaper)
                .put("zenRuleId", zenRuleId == null ? JSONObject.NULL : zenRuleId);
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
