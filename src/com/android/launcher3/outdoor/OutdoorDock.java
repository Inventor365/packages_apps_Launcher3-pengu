package com.android.launcher3.outdoor;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Process;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.InsetsFrameProvider;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import com.android.launcher3.R;
import com.android.launcher3.moments.MomentsStore;
import com.android.launcher3.util.Executors;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.Objects;

/**
 * The bar of pinned apps floating above whatever app is open. It claims navigation bar insets
 * so apps are laid out above it rather than under it.
 */
final class OutdoorDock {

    private static OutdoorDock sInstance;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final KeyguardManager mKeyguard;
    private final Binder mInsetsOwner = new Binder();
    private final Runnable mOnChanged = this::update;
    private final TaskStackChangeListener mTaskListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            refreshTopTask();
        }
    };
    private final KeyguardManager.KeyguardLockedStateListener mKeyguardListener =
            locked -> update();

    private View mRoot;
    private boolean mEnabled;
    private boolean mAttached;
    private String mTopPackage;
    // "Temporary full screen" lasts until another app comes to the front.
    private String mHiddenFor;

    private OutdoorDock(Context context) {
        mContext = new ContextThemeWrapper(context.getApplicationContext(),
                android.R.style.Theme_DeviceDefault_DayNight);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mKeyguard = mContext.getSystemService(KeyguardManager.class);
    }

    static synchronized OutdoorDock get(Context context) {
        if (sInstance == null) {
            sInstance = new OutdoorDock(context);
        }
        return sInstance;
    }

    void setEnabled(boolean enabled) {
        if (enabled == mEnabled) {
            update();
            return;
        }
        mEnabled = enabled;
        if (enabled) {
            TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskListener);
            mKeyguard.addKeyguardLockedStateListener(Executors.MAIN_EXECUTOR,
                    mKeyguardListener);
            MomentsStore.get(mContext).addListener(mOnChanged);
            OutdoorState.get(mContext).addListener(mOnChanged);
            refreshTopTask();
        } else {
            TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskListener);
            mKeyguard.removeKeyguardLockedStateListener(mKeyguardListener);
            MomentsStore.get(mContext).removeListener(mOnChanged);
            OutdoorState.get(mContext).removeListener(mOnChanged);
            detach();
        }
    }

    private void refreshTopTask() {
        ActivityManager.RunningTaskInfo task = ActivityManagerWrapper.getInstance()
                .getRunningTask();
        String top = task == null || task.topActivity == null ? null
                : task.topActivity.getPackageName();
        if (!Objects.equals(top, mTopPackage)) {
            mTopPackage = top;
            if (!Objects.equals(top, mHiddenFor)) {
                mHiddenFor = null;
            }
        }
        update();
    }

    private void update() {
        boolean show = mEnabled
                && mTopPackage != null
                && !mTopPackage.equals(mContext.getPackageName())
                && !mKeyguard.isKeyguardLocked()
                && MomentsStore.get(mContext).getActiveId() == null
                && !mTopPackage.equals(mHiddenFor);
        if (show) {
            attach();
            bind();
        } else {
            detach();
        }
    }

    private void attach() {
        if (mAttached) {
            return;
        }
        if (mRoot == null) {
            mRoot = LayoutInflater.from(mContext).inflate(R.layout.outdoor_dock, null);
            mRoot.findViewById(R.id.outdoor_dock_menu).setOnClickListener(this::showMenu);
        }
        int height = mContext.getResources().getDimensionPixelSize(R.dimen.outdoor_dock_height)
                + navigationBarHeight();
        mRoot.setPadding(0, 0, 0, navigationBarHeight());
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.setTitle("OutdoorDock");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.providedInsets = new InsetsFrameProvider[]{
                new InsetsFrameProvider(mInsetsOwner, 0, WindowInsets.Type.navigationBars())
                        .setSource(InsetsFrameProvider.SOURCE_DISPLAY)
                        .setInsetsSize(Insets.of(0, 0, 0, height)),
        };
        mWindowManager.addView(mRoot, lp);
        mAttached = true;
        mRoot.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                        applyScreenshotExclusion();
                        return true;
                    }
                });
    }

    private void applyScreenshotExclusion() {
        ViewRootImpl root = mRoot.getViewRootImpl();
        SurfaceControl surface = root == null ? null : root.getSurfaceControl();
        if (surface == null || !surface.isValid()) {
            return;
        }
        new SurfaceControl.Transaction()
                .setSkipScreenshot(surface, OutdoorState.get(mContext).hideInScreenshots())
                .apply();
    }

    private void detach() {
        if (mAttached) {
            mWindowManager.removeViewImmediate(mRoot);
            mAttached = false;
        }
    }

    private void bind() {
        LinearLayout apps = mRoot.findViewById(R.id.outdoor_dock_apps);
        apps.removeAllViews();
        LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        for (String app : OutdoorState.get(mContext).getApps()) {
            ComponentName component = ComponentName.unflattenFromString(app);
            LauncherActivityInfo info = component == null ? null : launcherApps.resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(component), Process.myUserHandle());
            if (info == null) {
                continue;
            }
            View item = inflater.inflate(R.layout.outdoor_dock_app, apps, false);
            ((ImageView) item.findViewById(R.id.outdoor_app_icon))
                    .setImageDrawable(info.getBadgedIcon(0));
            item.setContentDescription(info.getLabel());
            item.findViewById(R.id.outdoor_app_running).setVisibility(
                    component.getPackageName().equals(mTopPackage) ? View.VISIBLE
                            : View.INVISIBLE);
            item.setOnClickListener(v -> mContext.startActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
            apps.addView(item);
        }
        mRoot.findViewById(R.id.outdoor_dock_divider).setVisibility(
                apps.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        applyScreenshotExclusion();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(mContext, anchor, Gravity.END);
        menu.inflate(R.menu.outdoor_dock_menu);
        menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.outdoor_menu_full_screen) {
                mHiddenFor = mTopPackage;
                update();
            } else if (id == R.id.outdoor_menu_settings) {
                mContext.startActivity(new Intent(mContext, OutdoorSettings.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (id == R.id.outdoor_menu_exit) {
                OutdoorController.setEnabled(mContext, false);
            }
            return true;
        });
        menu.show();
    }

    private int navigationBarHeight() {
        int id = mContext.getResources().getIdentifier("navigation_bar_frame_height", "dimen",
                "android");
        return id == 0 ? 0 : mContext.getResources().getDimensionPixelSize(id);
    }
}
