package com.android.launcher3.moments;

import android.app.AutomaticZenRule;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.StatusBarManager;
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
import java.util.List;
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

    // The shade, Quick Settings and Recents stay out of reach, like Fairphone's switch. Tied to
    // this process's binder, so they come back by themselves if the launcher dies.
    private static final int DISABLE_FLAGS = StatusBarManager.DISABLE_EXPAND
            | StatusBarManager.DISABLE_NOTIFICATION_ICONS
            | StatusBarManager.DISABLE_RECENT
            | StatusBarManager.DISABLE_SEARCH;
    private static final int DISABLE2_FLAGS = StatusBarManager.DISABLE2_QUICK_SETTINGS
            | StatusBarManager.DISABLE2_NOTIFICATION_SHADE;
    private static StatusBarManager sStatusBar;

    public static void enter(Context context, Moment moment) {
        enter(context, moment, MomentsStore.SOURCE_MANUAL, 0);
    }

    public static void enter(Context context, Moment moment, String source, long endsAt) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> apply(app, moment, source, endsAt));
    }

    public static void exit(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> leave(app));
    }

    private static void leave(Context context) {
        MomentsStore store = MomentsStore.get(context);
        Moment active = store.getActive();
        undoRestrictions(context);
        if (active != null) {
            setRuleActive(context, active, false);
        }
        MomentsDeviceEffects.apply(context, null);
        lockSystemUi(context, false);
        store.setActive(null, null, 0);
        MomentsScheduler.armTimer(context, 0);
    }

    private static synchronized void lockSystemUi(Context context, boolean lock) {
        if (sStatusBar == null) {
            sStatusBar = context.getSystemService(StatusBarManager.class);
        }
        try {
            sStatusBar.disable(lock ? DISABLE_FLAGS : StatusBarManager.DISABLE_NONE);
            sStatusBar.disable2(lock ? DISABLE2_FLAGS : StatusBarManager.DISABLE2_NONE);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not " + (lock ? "lock" : "unlock") + " the status bar", e);
        }
    }

    public static void delete(Context context, Moment moment) {
        Context app = context.getApplicationContext();
        MomentsStore store = MomentsStore.get(app);
        boolean active = moment.id.equals(store.getActiveId());
        store.removeMoment(moment.id);
        WORKER.execute(() -> {
            if (active) {
                leave(app);
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
        MomentsStore store = MomentsStore.get(context);
        if (moment.id.equals(store.getActiveId())) {
            enter(context, moment, store.getSource(), store.getEndsAt());
        }
        MomentsScheduler.reschedule(context);
    }

    /**
     * Puts things right after the launcher restarts: suspensions and channel changes outlive
     * the process, so a crash mid-Moment must not leave apps blocked.
     */
    public static void reconcile(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            MomentsStore store = MomentsStore.get(app);
            Moment active = store.getActive();
            long endsAt = store.getEndsAt();
            if (active != null && endsAt > 0 && System.currentTimeMillis() >= endsAt) {
                leave(app);
            } else if (active == null) {
                undoRestrictions(app);
                MomentsDeviceEffects.apply(app, null);
                lockSystemUi(app, false);
            } else {
                setRuleActive(app, active, true);
                lockSystemUi(app, true);
                MomentsScheduler.armTimer(app, endsAt);
            }
            MomentsScheduler.reschedule(app);
        });
    }

    private static void apply(Context context, Moment moment, String source, long endsAt) {
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
        MomentsDeviceEffects.apply(context, moment);
        if (moment.appNotifications) {
            letAppsThrough(context, moment);
        }
        if (moment.blockOtherApps) {
            blockOtherApps(context, moment);
        }
        lockSystemUi(context, true);
        store.setActive(moment.id, source, endsAt);
        MomentsScheduler.armTimer(context, endsAt);
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
                .allowRepeatCallers(moment.repeatCallers)
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
                        .setShouldUseNightMode(moment.uiMode == Moment.UI_DARK)
                        .setShouldSuppressAmbientDisplay(moment.aodOff)
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
        for (String pkg : packagesOf(moment.apps)) {
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
        Set<String> keep = packagesOf(moment.apps);
        keep.addAll(packagesOf(moment.background));
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

    private static Set<String> packagesOf(List<String> apps) {
        Set<String> packages = new HashSet<>();
        for (String app : apps) {
            ComponentName component = MomentsUi.component(app);
            // Only this profile's apps can be suspended or let through.
            if (component != null && !MomentsUi.isWork(app)) {
                packages.add(component.getPackageName());
            }
        }
        return packages;
    }
}
