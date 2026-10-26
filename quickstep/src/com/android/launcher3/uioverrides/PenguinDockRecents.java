/* Copyright (C) 2026 The PenguinOS Project. SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.uioverrides;

import static com.android.launcher3.icons.cache.CacheLookupFlag.DEFAULT_LOOKUP_FLAG;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.quickstep.RecentsModel;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Three real recent apps alongside the native, editable pinned hotseat. */
public final class PenguinDockRecents extends LinearLayout {
    private final QuickstepLauncher launcher;
    private final List<View> recentIcons = new ArrayList<>();
    private int generation;
    private AutoCloseable recentChanges;
    private final RecentsModel.RecentTasksChangedListener recentListener = () -> post(this::refresh);
    private final Runnable reconnectRefresh = this::refresh;
    public PenguinDockRecents(QuickstepLauncher launcher) {
        super(launcher);
        this.launcher = launcher;
        setGravity(Gravity.CENTER_VERTICAL);
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        setOnClickListener(v -> refresh());
    }
    public void refresh() {
        final int request = ++generation;
        RecentsModel.INSTANCE.get(launcher).getTasks(groups -> {
            if (request != generation || !isAttachedToWindow()) return;
            Set<String> excluded = new HashSet<>();
            var pins = launcher.getHotseat().getShortcutsAndWidgets();
            for (int i = 0; i < pins.getChildCount(); i++) {
                if (pins.getChildAt(i).getTag() instanceof ItemInfo info
                        && info.getTargetComponent() != null) {
                    excluded.add(info.getTargetComponent().getPackageName() + ":" + info.user);
                }
            }
            List<Task> tasks = new ArrayList<>();
            for (int i = groups.size() - 1; i >= 0 && tasks.size() < 3; i--) {
                for (Task task : groups.get(i).getTasks()) {
                    if (task.key.baseIntent.getComponent() == null) continue;
                    String key = task.key.baseIntent.getComponent().getPackageName() + ":"
                            + UserHandle.of(task.key.userId);
                    if (excluded.add(key) && tasks.size() < 3) tasks.add(task);
                }
            }
            MODEL_EXECUTOR.execute(() -> {
                List<AppInfo> apps = new ArrayList<>();
                android.content.pm.LauncherApps launcherApps =
                        launcher.getSystemService(android.content.pm.LauncherApps.class);
                for (Task task : tasks) {
                    AppInfo info = new AppInfo();
                    info.componentName = task.key.baseIntent.getComponent();
                    info.user = UserHandle.of(task.key.userId);
                    info.intent = new Intent(task.key.baseIntent);
                    // A task can rest on an inner screen (Settings > About), which has no
                    // launcher icon of its own; show its app's icon and name instead.
                    var activities = launcherApps == null ? null : launcherApps.getActivityList(
                            info.componentName.getPackageName(), info.user);
                    if (activities != null && !activities.isEmpty() && activities.stream()
                            .noneMatch(a -> a.getComponentName().equals(info.componentName))) {
                        info.componentName = activities.get(0).getComponentName();
                        info.intent = AppInfo.makeLaunchIntent(info.componentName);
                    }
                    LauncherAppState.getInstance(launcher).getIconCache()
                            .getTitleAndIcon(info, DEFAULT_LOOKUP_FLAG);
                    apps.add(info);
                }
                launcher.getUiExecutor().execute(() -> {
                    if (request != generation || !isAttachedToWindow()) return;
                    for (View icon : recentIcons) removeView(icon);
                    recentIcons.clear();
                    for (int i = 0; i < apps.size(); i++) {
                        AppInfo app = apps.get(i);
                        Task task = tasks.get(i);
                        BubbleTextView icon = (BubbleTextView) launcher.getLayoutInflater()
                                .inflate(R.layout.app_icon, this, false);
                        icon.applyFromApplicationInfo(app);
                        icon.setText(null);
                        icon.setContainerTextVisibility(false);
                        icon.setCenterVertically(false);
                        icon.setCompoundDrawablePadding(0);
                        icon.setGravity(Gravity.CENTER);
                        icon.setContentDescription(app.title);
                        icon.setOnClickListener(v -> {
                            ActivityOptions options = ActivityOptions.makeBasic();
                            options.setLaunchDisplayId(launcher.getDisplayId());
                            if (!ActivityManagerWrapper.getInstance()
                                    .startActivityFromRecents(task.key, options)) {
                                launcher.startActivitySafely(v, app.intent, app);
                            }
                        });
                        icon.setOnLongClickListener(v -> {
                            launcher.getStateManager().goToState(LauncherState.OVERVIEW);
                            return true;
                        });
                        recentIcons.add(icon);
                        addView(icon, i, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
                    }
                    launcher.getHotseat().setDockCompanionSlotCount(apps.size());
                    requestLayout();
                });
            });
        });
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        RecentsModel model = RecentsModel.INSTANCE.get(launcher);
        model.registerRecentTasksChangedListener(recentListener);
        recentChanges = model.getTasksChanges().forEach(launcher.getUiExecutor(), value -> {
            refresh();
            return kotlin.Unit.INSTANCE;
        });
        refresh();
        // The SystemUI recents binder may connect after the first home frame on APK update.
        postDelayed(reconnectRefresh, 1500);
    }
    @Override protected void onDetachedFromWindow() {
        generation++;
        removeCallbacks(reconnectRefresh);
        RecentsModel.INSTANCE.get(launcher).unregisterRecentTasksChangedListener(recentListener);
        if (recentChanges != null) {
            try { recentChanges.close(); } catch (Exception e) {
                android.util.Log.w("PenguinDock", "Could not close recent task observer", e);
            }
            recentChanges = null;
        }
        super.onDetachedFromWindow();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int iconSize = launcher.getDeviceProfile().getWorkspaceProfile().getIconSizePx();
        float density = getResources().getDisplayMetrics().density;
        int minimumSlot = Math.max(Math.round(48 * density), iconSize + Math.round(2 * density));
        int visible = Math.min(recentIcons.size(), MeasureSpec.getSize(widthSpec) / minimumSlot);
        int slot = MeasureSpec.getSize(widthSpec) / Math.max(1, visible);
        for (int i = 0; i < recentIcons.size(); i++) {
            View icon = recentIcons.get(i);
            icon.setVisibility(i < visible ? VISIBLE : GONE);
            icon.getLayoutParams().width = slot;
            ((LayoutParams) icon.getLayoutParams()).weight = 0;
            icon.setPadding(0, Math.max(0, (MeasureSpec.getSize(heightSpec) - iconSize) / 2), 0, 0);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        if (recentIcons.isEmpty()) return;
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(0x55FFFFFF);
        float density = getResources().getDisplayMetrics().density;
        float x = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - density : density;
        canvas.drawRect(x, getHeight() * 0.12f, x + Math.max(1, density * 0.5f),
                getHeight() * 0.88f, paint);
    }
}
