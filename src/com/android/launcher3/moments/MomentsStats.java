package com.android.launcher3.moments;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.text.format.DateUtils;

import com.android.launcher3.R;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

final class MomentsStats {

    private static final long WEEK_MS = 7 * DateUtils.DAY_IN_MILLIS;
    private static final int WAKE_HOUR = 7;
    private static final int SLEEP_HOUR = 23;
    private static final long MIN_FOR_RATES_MS = 30 * DateUtils.MINUTE_IN_MILLIS;

    private MomentsStats() {}

    /** Null until a Moment has been used this week. */
    static String[] summary(Context context) {
        long now = System.currentTimeMillis();
        long from = now - WEEK_MS;
        List<long[]> sessions = MomentsStore.get(context).getSessions();
        long inMoments = 0;
        for (long[] s : sessions) {
            inMoments += Math.max(0, Math.min(s[1], now) - Math.max(s[0], from));
        }
        if (inMoments <= 0) {
            return null;
        }
        long minutes = inMoments / DateUtils.MINUTE_IN_MILLIS;
        String duration = minutes >= 60
                ? context.getString(R.string.moments_duration_hm, minutes / 60, minutes % 60)
                : context.getString(R.string.moments_duration_m, minutes);
        String time = context.getString(R.string.moments_stats_time, duration);
        if (inMoments < MIN_FOR_RATES_MS) {
            return new String[]{time};
        }

        int unlocksIn = 0;
        int unlocksOut = 0;
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        UsageEvents events = usage.queryEvents(from, now);
        UsageEvents.Event event = new UsageEvents.Event();
        Calendar at = Calendar.getInstance();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() != UsageEvents.Event.KEYGUARD_HIDDEN) {
                continue;
            }
            long t = event.getTimeStamp();
            if (inside(sessions, t)) {
                unlocksIn++;
            } else {
                at.setTimeInMillis(t);
                int hour = at.get(Calendar.HOUR_OF_DAY);
                if (hour >= WAKE_HOUR && hour < SLEEP_HOUR) {
                    unlocksOut++;
                }
            }
        }
        // Compare against waking hours only, so nights don't flatter the rest of the day.
        double awakeHours = 7.0 * (SLEEP_HOUR - WAKE_HOUR);
        double hoursIn = inMoments / (double) DateUtils.HOUR_IN_MILLIS;
        double hoursOut = Math.max(1, awakeHours - hoursIn);
        String rates = context.getString(R.string.moments_stats_unlocks,
                String.format(Locale.getDefault(), "%.1f", unlocksIn / hoursIn),
                String.format(Locale.getDefault(), "%.1f", unlocksOut / hoursOut));
        return new String[]{time, rates};
    }

    private static boolean inside(List<long[]> sessions, long t) {
        for (long[] s : sessions) {
            if (t >= s[0] && t < s[1]) {
                return true;
            }
        }
        return false;
    }
}
