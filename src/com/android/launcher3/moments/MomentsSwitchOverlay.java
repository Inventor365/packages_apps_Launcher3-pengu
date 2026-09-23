/*
 * Timing and look from fairphone/fairphone-moments (SwitchStateChangeOverlay.kt).
 * SPDX-FileCopyrightText: 2025. FairPhone B.V. SPDX-License-Identifier: EUPL-1.2
 */
package com.android.launcher3.moments;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.launcher3.Insettable;
import com.android.launcher3.R;

import java.util.Objects;

/** Briefly says which Moment was turned on or off, next to a lime bar sliding along the edge. */
public class MomentsSwitchOverlay extends FrameLayout implements Insettable {

    private static final long ENTER_MS = 500;
    private static final long STILL_MS = 1000;
    private static final long EXIT_MS = 500;
    private static final PathInterpolator LINEAR_OUT_SLOW_IN =
            new PathInterpolator(0, 0, 0.2f, 1);

    private final Runnable mOnChanged = this::onMomentsChanged;
    private String mActiveId;
    private View mScrim;
    private View mLabel;
    private ImageView mIcon;
    private TextView mText;
    private View mBar;
    private Runnable mEnter;
    private Runnable mExit;
    private Runnable mHide;

    public MomentsSwitchOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mScrim = findViewById(R.id.moments_switch_scrim);
        mLabel = findViewById(R.id.moments_switch_label);
        mIcon = findViewById(R.id.moments_switch_icon);
        mText = findViewById(R.id.moments_switch_text);
        mBar = findViewById(R.id.moments_switch_bar);
    }

    @Override
    public void setInsets(Rect insets) { }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mActiveId = MomentsStore.get(getContext()).getActiveId();
        MomentsStore.get(getContext()).addListener(mOnChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        MomentsStore.get(getContext()).removeListener(mOnChanged);
        super.onDetachedFromWindow();
    }

    private void onMomentsChanged() {
        MomentsStore store = MomentsStore.get(getContext());
        String activeId = store.getActiveId();
        if (Objects.equals(activeId, mActiveId)) {
            return;
        }
        Moment shown = store.getMoment(activeId != null ? activeId : mActiveId);
        mActiveId = activeId;
        if (shown != null) {
            play(shown, activeId != null);
        }
    }

    private void play(Moment moment, boolean enabled) {
        removeCallbacks(mEnter);
        removeCallbacks(mExit);
        removeCallbacks(mHide);
        mIcon.setImageResource(MomentsUi.iconRes(moment.icon));
        mText.setText(getContext().getString(enabled ? R.string.moments_switch_enabled
                : R.string.moments_switch_disabled, moment.name));
        for (View view : new View[]{mScrim, mLabel, mIcon, mBar}) {
            view.animate().cancel();
        }
        mScrim.setAlpha(0);
        mLabel.setAlpha(0);
        mIcon.setRotation(0);
        mBar.setScaleY(0);
        mBar.setAlpha(0.3f);
        setVisibility(VISIBLE);
        // The bar grows from the end it would be switched towards and leaves from the other.
        float barHeight = 92 * getResources().getDisplayMetrics().density;
        mBar.setPivotY(enabled ? 0 : barHeight);
        mEnter = () -> {
            mScrim.animate().alpha(1).setDuration(300).start();
            mLabel.animate().alpha(1).setDuration(ENTER_MS).setInterpolator(LINEAR_OUT_SLOW_IN)
                    .start();
            mIcon.animate().rotation(enabled ? 180 : -180).setStartDelay(ENTER_MS / 2)
                    .setDuration(ENTER_MS).setInterpolator(LINEAR_OUT_SLOW_IN).start();
            mBar.animate().scaleY(1).alpha(1).setDuration(ENTER_MS)
                    .setInterpolator(LINEAR_OUT_SLOW_IN).start();
        };
        mExit = () -> {
            mBar.setPivotY(enabled ? barHeight : 0);
            mBar.animate().scaleY(0).setDuration(EXIT_MS).setInterpolator(LINEAR_OUT_SLOW_IN)
                    .start();
            mLabel.animate().alpha(0).setDuration(EXIT_MS).setInterpolator(LINEAR_OUT_SLOW_IN)
                    .start();
            mScrim.animate().alpha(0).setDuration(EXIT_MS).start();
        };
        mHide = () -> setVisibility(GONE);
        postOnAnimationDelayed(mEnter, ENTER_MS);
        postOnAnimationDelayed(mExit, ENTER_MS + STILL_MS);
        postOnAnimationDelayed(mHide, ENTER_MS + STILL_MS + EXIT_MS * 2);
    }
}
