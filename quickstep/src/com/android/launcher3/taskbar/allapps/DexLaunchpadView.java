/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.taskbar.allapps;

import android.graphics.Rect;
import android.view.MotionEvent;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Insettable;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.taskbar.overlay.TaskbarOverlayContext;
import com.android.launcher3.views.BaseDragLayer;
import com.penguin.dex.launcher.Launchpad;
import com.penguin.dex.launcher.LaunchpadApp;

import java.util.ArrayList;
import java.util.List;

import kotlin.Unit;

/** Hosts PenguinDex's Launchpad, a DeX desktop's app drawer, in the taskbar's overlay window. */
public class DexLaunchpadView extends AbstractFloatingView implements Insettable {

    private final TaskbarOverlayContext mContext;
    private final Launchpad mLaunchpad;

    private DexLaunchpadView(TaskbarOverlayContext context, AppInfo[] apps) {
        super(context, null);
        mContext = context;
        mLaunchpad = new Launchpad(context);
        addView(mLaunchpad, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        List<LaunchpadApp> items = new ArrayList<>(apps.length);
        for (AppInfo app : apps) {
            items.add(new LaunchpadApp(app.title, app.newIcon(context), app));
        }
        mLaunchpad.setApps(items);
        mLaunchpad.setOnLaunch((app, view) -> {
            // The taskbar launches what the view's tag names.
            view.setTag(app.getTag());
            context.getItemOnClickListener().onClick(view);
            close(true);
            return Unit.INSTANCE;
        });
        mLaunchpad.setOnDismiss(() -> {
            close(true);
            return Unit.INSTANCE;
        });
    }

    /** Opens the Launchpad over the DeX desktop with [apps]. */
    static DexLaunchpadView show(TaskbarOverlayContext context, AppInfo[] apps) {
        DexLaunchpadView view = new DexLaunchpadView(context, apps);
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        context.getDragLayer().addView(view, lp);
        view.mIsOpen = true;
        view.mLaunchpad.animateIn();
        return view;
    }

    @Override
    public void setInsets(Rect insets) {
        mLaunchpad.setInsets(insets.top, insets.bottom);
    }

    @Override
    protected void handleClose(boolean animate) {
        if (!mIsOpen) return;
        mIsOpen = false;
        if (animate) {
            mLaunchpad.animateOut(() -> mContext.getDragLayer().removeView(this));
        } else {
            mContext.getDragLayer().removeView(this);
        }
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_TASKBAR_ALL_APPS) != 0;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        return false;
    }
}
