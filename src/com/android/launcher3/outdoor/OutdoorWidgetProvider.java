package com.android.launcher3.outdoor;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.android.launcher3.R;

public class OutdoorWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_TOGGLE = "com.android.launcher3.outdoor.TOGGLE";
    private static final int ON_TEXT = 0xFF1B1B1D;

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context,
                OutdoorWidgetProvider.class));
        if (ids.length > 0) {
            manager.updateAppWidget(ids, views(context));
        }
    }

    static boolean isAdded(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, OutdoorWidgetProvider.class)).length > 0;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, views(context));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            OutdoorController.setEnabled(context, !OutdoorState.get(context).isEnabled());
            return;
        }
        super.onReceive(context, intent);
    }

    private static RemoteViews views(Context context) {
        boolean on = OutdoorState.get(context).isEnabled();
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.outdoor_widget);
        views.setTextViewText(R.id.outdoor_widget_state,
                context.getString(on ? R.string.outdoor_on : R.string.outdoor_off));
        views.setInt(R.id.outdoor_widget_root, "setBackgroundResource",
                on ? R.drawable.outdoor_widget_bg_on : R.drawable.outdoor_widget_bg);
        // The "on" background is the same bright yellow in both themes.
        int text = on ? ON_TEXT : context.getColor(R.color.outdoor_dock_icon);
        views.setTextColor(R.id.outdoor_widget_title, text);
        views.setTextColor(R.id.outdoor_widget_state, text);
        views.setInt(R.id.outdoor_widget_icon, "setColorFilter", text);
        views.setOnClickPendingIntent(R.id.outdoor_widget_root, PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_TOGGLE).setClass(context,
                        OutdoorWidgetProvider.class), PendingIntent.FLAG_IMMUTABLE));
        return views;
    }
}
