package com.android.launcher3.moments;

import android.app.AutomaticZenRule;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.SuspendDialogInfo;
import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.provider.AlarmClock;
import android.provider.Telephony;
import android.service.notification.Condition;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenPolicy;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.launcher3.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All work runs on one background thread, in order. */
public final class MomentsController {

    private static final String TAG = "Moments";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private MomentsController() {}

    public static void enter(Context context, Moment moment) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> apply(app, moment));
    }

    public static void exit(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            MomentsStore store = MomentsStore.get(app);
            Moment active = store.getActive();
            undoRestrictions(app);
            if (active != null) {
                setRuleActive(app, active, false);
            }
            store.setActive(null);
        });
    }

    public static void delete(Context context, Moment moment) {
        Context app = context.getApplicationContext();
        MomentsStore store = MomentsStore.get(app);
        boolean active = moment.id.equals(store.getActiveId());
        store.removeMoment(moment.id);
        WORKER.execute(() -> {
            if (active) {
                undoRestrictions(app);
                store.setActive(null);
            }
            if (moment.zenRuleId != null) {
                try {
                    app.getSystemService(NotificationManager.class)
                            .removeAutomaticZenRule(moment.zenRuleId);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not remove the mode for " + moment.name, e);
                }
            }
        });
    }

    public static void onMomentChanged(Context context, Moment moment) {
        if (moment.id.equals(MomentsStore.get(context).getActiveId())) {
            enter(context, moment);
        }
    }

    /**
     * Puts things right after the launcher restarts: suspensions and channel changes outlive
     * the process, so a crash mid-Moment must not leave apps blocked.
     */
    public static void reconcile(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            Moment active = MomentsStore.get(app).getActive();
            if (active == null) {
                undoRestrictions(app);
            } else {
                setRuleActive(app, active, true);
            }
        });
    }

    private static void apply(Context context, Moment moment) {
        MomentsStore store = MomentsStore.get(context);
        Moment previous = store.getActive();
        undoRestrictions(context);
        if (previous != null && !previous.id.equals(moment.id)) {
            setRuleActive(context, previous, false);
        }
        if (!updateRule(context, moment)) {
            Log.w(TAG, "Could not set up the mode for " + moment.name);
        }
        setRuleActive(context, moment, true);
        if (moment.appNotifications) {
            letAppsThrough(context, moment);
        }
        if (moment.blockOtherApps) {
            blockOtherApps(context, moment);
        }
        store.setActive(moment.id);
    }

    private static Uri conditionId(Context context, Moment moment) {
        return new Uri.Builder().scheme(Condition.SCHEME).authority(context.getPackageName())
                .appendPath("moment").appendPath(moment.id).build();
    }

    private static boolean updateRule(Context context, Moment moment) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        int conversations = moment.messages == ZenPolicy.PEOPLE_TYPE_NONE
                ? ZenPolicy.CONVERSATION_SENDERS_NONE
                : moment.messages == ZenPolicy.PEOPLE_TYPE_ANYONE
                        ? ZenPolicy.CONVERSATION_SENDERS_ANYONE
                        : ZenPolicy.CONVERSATION_SENDERS_IMPORTANT;
        ZenPolicy policy = new ZenPolicy.Builder()
                .allowCalls(moment.calls)
                .allowRepeatCallers(moment.calls != ZenPolicy.PEOPLE_TYPE_NONE)
                .allowMessages(moment.messages)
                .allowConversations(conversations)
                .allowAlarms(true)
                .allowMedia(true)
                .allowSystem(false)
                .allowReminders(false)
                .allowEvents(false)
                .allowPriorityChannels(moment.appNotifications)
                .hideAllVisualEffects()
                .build();
        AutomaticZenRule rule = new AutomaticZenRule.Builder(moment.name,
                conditionId(context, moment))
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setConfigurationActivity(new ComponentName(context, MomentsActivity.class))
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(policy)
                .setDeviceEffects(new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(moment.grayscale)
                        .setShouldDimWallpaper(moment.dimWallpaper)
                        .build())
                .setIconResId(MomentsUi.iconRes(moment.icon))
                // Leaving goes through the launcher pill so blocked apps come back with it.
                .setManualInvocationAllowed(false)
                .build();
        try {
            if (moment.zenRuleId != null && nm.getAutomaticZenRule(moment.zenRuleId) != null) {
                return nm.updateAutomaticZenRule(moment.zenRuleId, rule);
            }
            moment.zenRuleId = nm.addAutomaticZenRule(rule);
            MomentsStore.get(context).putMoment(moment);
            return moment.zenRuleId != null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Mode update failed", e);
            return false;
        }
    }

    private static void setRuleActive(Context context, Moment moment, boolean active) {
        if (moment.zenRuleId == null) {
            return;
        }
        try {
            context.getSystemService(NotificationManager.class).setAutomaticZenRuleState(
                    moment.zenRuleId, new Condition(conditionId(context, moment), moment.name,
                            active ? Condition.STATE_TRUE : Condition.STATE_FALSE));
        } catch (RuntimeException e) {
            Log.w(TAG, "Mode state change failed", e);
        }
    }

    /**
     * Modes only let through channels marked as bypassing DND, and that flag is global, so it is
     * set on the Moment's apps for as long as the Moment is on and restored after.
     */
    private static void letAppsThrough(Context context, Moment moment) {
        INotificationManager inm = NotificationManager.getService();
        PackageManager pm = context.getPackageManager();
        Set<String> changed = new HashSet<>();
        for (String pkg : packagesOf(moment)) {
            int uid;
            try {
                uid = pm.getPackageUid(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            try {
                ParceledListSlice<NotificationChannel> channels =
                        inm.getNotificationChannelsForPackage(pkg, uid, false);
                for (NotificationChannel channel : channels.getList()) {
                    if (channel.canBypassDnd()) {
                        continue;
                    }
                    changed.add(pkg + "/" + uid + "/" + channel.getId());
                    // Recorded first, so a crash here still gets undone by reconcile().
                    MomentsStore.get(context).setBypassedChannels(changed);
                    channel.setBypassDnd(true);
                    inm.updateNotificationChannelForPackage(pkg, uid, channel);
                }
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Could not let " + pkg + " through", e);
            }
        }
    }

    private static void blockOtherApps(Context context, Moment moment) {
        Set<String> keep = packagesOf(moment);
        keep.add(context.getPackageName());
        keep.add(SETTINGS_PACKAGE);
        keep.add(context.getSystemService(TelecomManager.class).getDefaultDialerPackage());
        if (moment.messages != ZenPolicy.PEOPLE_TYPE_NONE) {
            keep.add(Telephony.Sms.getDefaultSmsPackage(context));
        }
        // Suspended apps can't start activities, and an alarm has to be able to ring.
        for (String action : new String[]{AlarmClock.ACTION_SET_ALARM,
                AlarmClock.ACTION_SHOW_ALARMS}) {
            for (ResolveInfo info : context.getPackageManager().queryIntentActivities(
                    new Intent(action), 0)) {
                keep.add(info.activityInfo.packageName);
            }
        }
        InputMethodManager imm = context.getSystemService(InputMethodManager.class);
        for (InputMethodInfo ime : imm.getEnabledInputMethodList()) {
            keep.add(ime.getPackageName());
        }

        Set<String> block = new HashSet<>();
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        for (LauncherActivityInfo info : launcherApps.getActivityList(null,
                Process.myUserHandle())) {
            String pkg = info.getComponentName().getPackageName();
            if (!keep.contains(pkg)) {
                block.add(pkg);
            }
        }
        if (block.isEmpty()) {
            return;
        }
        MomentsStore.get(context).setSuspended(block);
        SuspendDialogInfo dialog = new SuspendDialogInfo.Builder()
                .setIcon(MomentsUi.iconRes(moment.icon))
                .setTitle(context.getString(R.string.moments_paused_title))
                .setMessage(context.getString(R.string.moments_paused_message, "%1$s",
                        moment.name))
                .build();
        try {
            context.getPackageManager().setPackagesSuspended(block.toArray(new String[0]), true,
                    null, null, dialog);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not pause other apps", e);
        }
    }

    private static void undoRestrictions(Context context) {
        MomentsStore store = MomentsStore.get(context);
        Set<String> suspended = store.getSuspended();
        if (!suspended.isEmpty()) {
            try {
                context.getPackageManager().setPackagesSuspended(
                        suspended.toArray(new String[0]), false, null, null,
                        (SuspendDialogInfo) null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not unpause apps", e);
            }
            store.setSuspended(Set.of());
        }
        Set<String> channels = store.getBypassedChannels();
        if (channels.isEmpty()) {
            return;
        }
        INotificationManager inm = NotificationManager.getService();
        Set<String> failed = new HashSet<>();
        Map<String, Set<String>> byApp = new HashMap<>();
        for (String entry : channels) {
            int split = entry.indexOf('/', entry.indexOf('/') + 1);
            byApp.computeIfAbsent(entry.substring(0, split), k -> new HashSet<>())
                    .add(entry.substring(split + 1));
        }
        for (Map.Entry<String, Set<String>> app : byApp.entrySet()) {
            String[] pkgUid = app.getKey().split("/");
            try {
                int uid = Integer.parseInt(pkgUid[1]);
                // The single-channel getter is closed to the launcher; the list one is not.
                ParceledListSlice<NotificationChannel> list =
                        inm.getNotificationChannelsForPackage(pkgUid[0], uid, false);
                for (NotificationChannel channel : list.getList()) {
                    if (app.getValue().contains(channel.getId()) && channel.canBypassDnd()) {
                        channel.setBypassDnd(false);
                        inm.updateNotificationChannelForPackage(pkgUid[0], uid, channel);
                    }
                }
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Could not restore " + app.getKey(), e);
                for (String id : app.getValue()) {
                    failed.add(app.getKey() + "/" + id);
                }
            }
        }
        store.setBypassedChannels(failed);
    }

    private static Set<String> packagesOf(Moment moment) {
        Set<String> packages = new HashSet<>();
        for (String app : moment.apps) {
            ComponentName component = ComponentName.unflattenFromString(app);
            if (component != null) {
                packages.add(component.getPackageName());
            }
        }
        return packages;
    }
}
