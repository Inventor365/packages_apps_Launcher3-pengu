package com.android.launcher3.moments;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.android.launcher3.R;

import java.util.Calendar;

public final class MomentsScheduler {

    static final String ACTION_SCHEDULE = "com.android.launcher3.moments.SCHEDULE";
    static final String ACTION_TIMER = "com.android.launcher3.moments.TIMER";

    private MomentsScheduler() {}

    static void armTimer(Context context, long endsAt) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent intent = broadcast(context, ACTION_TIMER);
        if (endsAt <= 0) {
            alarms.cancel(intent);
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, intent);
        }
    }

    static void onTimer(Context context) {
        MomentsStore store = MomentsStore.get(context);
        Moment active = store.getActive();
        long endsAt = store.getEndsAt();
        if (active != null && endsAt > 0 && System.currentTimeMillis() >= endsAt) {
            MomentsController.exit(context);
            Toast.makeText(context, context.getString(R.string.moments_ended, active.name),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static void reschedule(Context context) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (Moment moment : MomentsStore.get(context).getMoments()) {
            if (moment.scheduleEnabled && moment.scheduleDays != 0) {
                next = Math.min(next, nextBoundary(moment, now));
            }
        }
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent intent = broadcast(context, ACTION_SCHEDULE);
        if (next == Long.MAX_VALUE) {
            alarms.cancel(intent);
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent);
        }
    }

    /** Starts the Moment whose window is open, or ends one the schedule started. */
    static void onSchedule(Context context) {
        MomentsStore store = MomentsStore.get(context);
        long now = System.currentTimeMillis();
        Moment due = null;
        for (Moment moment : store.getMoments()) {
            if (moment.scheduleEnabled && inWindow(moment, now)) {
                due = moment;
                break;
            }
        }
        Moment active = store.getActive();
        if (active == null && due != null) {
            MomentsController.enter(context, due, MomentsStore.SOURCE_SCHEDULE, 0);
        } else if (active != null && MomentsStore.SOURCE_SCHEDULE.equals(store.getSource())
                && (due == null || !due.id.equals(active.id))) {
            MomentsController.exit(context);
        }
        reschedule(context);
    }

    static void onBluetooth(Context context, BluetoothDevice device, boolean connected) {
        if (!isCar(device)) {
            return;
        }
        MomentsStore store = MomentsStore.get(context);
        Moment active = store.getActive();
        if (connected && active == null) {
            for (Moment moment : store.getMoments()) {
                if (moment.carTrigger) {
                    MomentsController.enter(context, moment, MomentsStore.SOURCE_CAR, 0);
                    return;
                }
            }
        } else if (!connected && active != null
                && MomentsStore.SOURCE_CAR.equals(store.getSource())) {
            MomentsController.exit(context);
        }
    }

    private static boolean isCar(BluetoothDevice device) {
        try {
            BluetoothClass type = device == null ? null : device.getBluetoothClass();
            if (type == null) {
                return false;
            }
            int deviceClass = type.getDeviceClass();
            return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
                    || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE;
        } catch (SecurityException e) {
            return false;
        }
    }

    static boolean inWindow(Moment moment, long time) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(time);
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean overnight = moment.scheduleEnd <= moment.scheduleStart;
        if (!overnight) {
            return onDay(moment, now) && minute >= moment.scheduleStart
                    && minute < moment.scheduleEnd;
        }
        if (minute >= moment.scheduleStart) {
            return onDay(moment, now);
        }
        // Early morning belongs to the window that started the day before.
        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        return minute < moment.scheduleEnd && onDay(moment, yesterday);
    }

    private static boolean onDay(Moment moment, Calendar day) {
        return (moment.scheduleDays & (1 << (day.get(Calendar.DAY_OF_WEEK) - 1))) != 0;
    }

    private static long nextBoundary(Moment moment, long now) {
        long next = Long.MAX_VALUE;
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(now);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 8; offset++) {
            for (int minute : new int[]{moment.scheduleStart, moment.scheduleEnd}) {
                Calendar at = (Calendar) day.clone();
                at.add(Calendar.DAY_OF_YEAR, offset);
                at.set(Calendar.HOUR_OF_DAY, minute / 60);
                at.set(Calendar.MINUTE, minute % 60);
                if (at.getTimeInMillis() > now) {
                    next = Math.min(next, at.getTimeInMillis());
                }
            }
            if (next != Long.MAX_VALUE) {
                break;
            }
        }
        return next;
    }

    private static PendingIntent broadcast(Context context, String action) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(action).setClass(context, MomentsTriggerReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
