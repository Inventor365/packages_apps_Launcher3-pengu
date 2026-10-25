/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep.util;


import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.Animator;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation.OnAnimationEndListener;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.QuickstepTransitionManager;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.DynamicResource;
import com.android.quickstep.SurfaceReleaseCheck;
import com.android.systemui.plugins.ResourceProvider;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;


/**
 * Applies spring forces to animate from a starting rect to a target rect,
 * while providing update callbacks to the caller.
 */
public class RectFSpringAnim extends SurfaceReleaseCheck {

    private static final FloatPropertyCompat<RectFSpringAnim> RECT_CENTER_X =
            new FloatPropertyCompat<RectFSpringAnim>("rectCenterXSpring") {
                @Override
                public float getValue(RectFSpringAnim anim) {
                    return anim.mCurrentCenterX;
                }

                @Override
                public void setValue(RectFSpringAnim anim, float currentCenterX) {
                    anim.mCurrentCenterX = currentCenterX;
                    anim.onUpdate();
                }
            };

    private static final FloatPropertyCompat<RectFSpringAnim> RECT_Y =
            new FloatPropertyCompat<RectFSpringAnim>("rectYSpring") {
                @Override
                public float getValue(RectFSpringAnim anim) {
                    return anim.mCurrentY;
                }

                @Override
                public void setValue(RectFSpringAnim anim, float y) {
                    anim.mCurrentY = y;
                    anim.onUpdate();
                }
            };

    private static final FloatPropertyCompat<RectFSpringAnim> RECT_SCALE_PROGRESS =
            new FloatPropertyCompat<RectFSpringAnim>("rectScaleProgress") {
                @Override
                public float getValue(RectFSpringAnim object) {
                    return object.mCurrentScaleProgress;
                }

                @Override
                public void setValue(RectFSpringAnim object, float value) {
                    object.mCurrentScaleProgress = value;
                    object.onUpdate();
                }
            };

    private final RectF mStartRect;
    private final RectF mTargetRect;
    private final RectF mCurrentRect = new RectF();
    private final List<OnUpdateListener> mOnUpdateListeners = new ArrayList<>();
    private final List<Animator.AnimatorListener> mAnimatorListeners = new ArrayList<>();

    private float mCurrentCenterX;
    private float mCurrentY;
    // If true, tracking the bottom of the rects, else tracking the top.
    private float mCurrentScaleProgress;
    private SpringAnimation mRectXSpring;
    private SpringAnimation mRectYSpring;
    private SpringAnimation mRectScaleAnim;
    private boolean mAnimsStarted;
    private boolean mPaused;
    private boolean mRectXAnimEnded;
    private boolean mRectYAnimEnded;
    private boolean mRectScaleAnimEnded;

    /**
     * Indicates which part of the start & target rects we are interpolating between.
     */
    public static final int TRACKING_TOP = 0;
    public static final int TRACKING_CENTER = 1;
    public static final int TRACKING_BOTTOM = 2;

    @Retention(SOURCE)
    @IntDef(value = {TRACKING_TOP,
                    TRACKING_CENTER,
                    TRACKING_BOTTOM})
    public @interface Tracking{}

    @Tracking
    public final int mTracking;
    protected final float mStiffnessX;
    protected final float mStiffnessY;
    protected final float mDampingX;
    protected final float mDampingY;
    protected final float mRectStiffness;
    private final float mRectDamping;
    private final boolean mCos;
    private long mCosStartTime;

    public RectFSpringAnim(SpringConfig config) {
        mStartRect = config.startRect;
        mTargetRect = config.targetRect;
        mCurrentCenterX = mStartRect.centerX();

        setCanRelease(true);

        mTracking = config.tracking;
        mStiffnessX = config.stiffnessX;
        mStiffnessY = config.stiffnessY;
        mDampingX = config.dampingX;
        mDampingY = config.dampingY;
        mRectStiffness = config.rectStiffness;
        mRectDamping = config.rectDamping;
        mCos = config.cos;

        mCurrentY = getTrackedYFromRect(mStartRect);
    }

    public RectF getTargetRect() {
        return mTargetRect;
    }

    private float getTrackedYFromRect(RectF rect) {
        switch (mTracking) {
            case TRACKING_TOP:
                return rect.top;
            case TRACKING_BOTTOM:
                return rect.bottom;
            case TRACKING_CENTER:
            default:
                return rect.centerY();
        }
    }

    public void onTargetPositionChanged() {
        if (isEnded() || mPaused) {
            return;
        }

        if (mRectXSpring != null) {
            mRectXSpring.animateToFinalPosition(mTargetRect.centerX());
            mRectXAnimEnded = false;
        }

        if (mRectYSpring != null) {
            switch (mTracking) {
                case TRACKING_TOP:
                    mRectYSpring.animateToFinalPosition(mTargetRect.top);
                    break;
                case TRACKING_BOTTOM:
                    mRectYSpring.animateToFinalPosition(mTargetRect.bottom);
                    break;
                case TRACKING_CENTER:
                    mRectYSpring.animateToFinalPosition(mTargetRect.centerY());
                    break;
            }
            mRectYAnimEnded = false;
        }
    }

    public void addOnUpdateListener(OnUpdateListener onUpdateListener) {
        mOnUpdateListeners.add(onUpdateListener);
    }

    public void addAnimatorListener(Animator.AnimatorListener animatorListener) {
        mAnimatorListeners.add(animatorListener);
    }

    /**
     * Starts the fling/spring animation.
     * @param context The activity context.
     * @param velocityPxPerMs Velocity of swipe in px/ms.
     */
    public void start(Context context, @Nullable DeviceProfile profile, PointF velocityPxPerMs) {
        startSprings(context, velocityPxPerMs);
        for (Animator.AnimatorListener animatorListener : mAnimatorListeners) {
            animatorListener.onAnimationStart(null);
        }
    }

    /**
     * Holds the rect where it is, without ending the animation, so it can follow a finger.
     */
    public void pause() {
        if (!mAnimsStarted || mPaused || isEnded()) {
            return;
        }
        mPaused = true;
        mRectXSpring.cancel();
        mRectYSpring.cancel();
        mRectScaleAnim.cancel();
    }

    /**
     * Moves a paused rect by the given offset. Dragging down grows it back towards its start size.
     */
    public void dragBy(float dx, float dy) {
        if (!mPaused) {
            return;
        }
        mCurrentCenterX += dx;
        mCurrentY += dy;
        mCurrentScaleProgress = Utilities.boundToRange(
                mCurrentScaleProgress - dy / mStartRect.height(), 0f, 1f);
        onUpdate();
    }

    /**
     * Springs a paused rect on to its target, starting at the given velocity.
     */
    public void resume(Context context, PointF velocityPxPerMs) {
        if (!mPaused) {
            return;
        }
        mPaused = false;
        mRectXAnimEnded = false;
        mRectYAnimEnded = false;
        mRectScaleAnimEnded = false;
        startSprings(context, velocityPxPerMs);
    }

    public boolean isRunning() {
        return mAnimsStarted && !isEnded();
    }

    /**
     * How far the window has given way to its icon. ColorOS 17 starts the swap a couple of frames
     * after the finger lifts and has it half done at 75 ms and all but done by 175 ms.
     */
    public float getCosFadeProgress() {
        if (mCosStartTime == 0) {
            return 0f;
        }
        float t = Math.max(0, SystemClock.uptimeMillis() - mCosStartTime - 17) / 1000f;
        float wt = 30f * t;
        return 1 - (1 + wt) * (float) Math.exp(-wt);
    }

    public boolean isCos() {
        return mCos;
    }

    public RectF getCurrentRect() {
        return mCurrentRect;
    }

    private void startSprings(Context context, PointF velocityPxPerMs) {
        // Only tell caller that we ended if both x and y animations have ended.
        OnAnimationEndListener onXEndListener = ((animation, canceled, centerX, velocityX) -> {
            if (mPaused) return;
            mRectXAnimEnded = true;
            maybeOnEnd();
        });
        OnAnimationEndListener onYEndListener = ((animation, canceled, centerY, velocityY) -> {
            if (mPaused) return;
            mRectYAnimEnded = true;
            maybeOnEnd();
        });

        float xVelocityPxPerS = velocityPxPerMs.x * 1000;
        float yVelocityPxPerS = velocityPxPerMs.y * 1000;
        float startX = mCurrentCenterX;
        float endX = mTargetRect.centerX();
        float startY = mCurrentY;
        float endY = getTrackedYFromRect(mTargetRect);
        float minVisibleChange = Math.abs(1f / mStartRect.height());

        ResourceProvider rp = DynamicResource.provider(context);
        long minVelocityXPxPerS = rp.getInt(R.dimen.swipe_up_min_velocity_x_px_per_s);
        long maxVelocityXPxPerS = rp.getInt(R.dimen.swipe_up_max_velocity_x_px_per_s);
        long minVelocityYPxPerS = rp.getInt(R.dimen.swipe_up_min_velocity_y_px_per_s);
        long maxVelocityYPxPerS = rp.getInt(R.dimen.swipe_up_max_velocity_y_px_per_s);
        float fallOffFactor = rp.getFloat(R.dimen.swipe_up_max_velocity_fall_off_factor);

        // We want the actual initial velocity to never dip below the minimum, and to taper off
        // once it's above the soft cap so that we can prevent the window from flying off
        // screen, while maintaining a natural feel.
        xVelocityPxPerS = adjustVelocity(
                xVelocityPxPerS, minVelocityXPxPerS, maxVelocityXPxPerS, fallOffFactor);
        yVelocityPxPerS = adjustVelocity(
                yVelocityPxPerS, minVelocityYPxPerS, maxVelocityYPxPerS, fallOffFactor);

        if (mCos) {
            if ((endX - startX) * xVelocityPxPerS < 0) xVelocityPxPerS *= 0.25f;
            if ((endY - startY) * yVelocityPxPerS < 0) yVelocityPxPerS *= 0.25f;
        }

        mRectXSpring =
                new SpringAnimation(this, RECT_CENTER_X)
                        .setSpring(
                                new SpringForce(endX)
                                        .setStiffness(mStiffnessX)
                                        .setDampingRatio(mDampingX)
                        ).setStartValue(startX)
                        .setStartVelocity(xVelocityPxPerS)
                        .addEndListener(onXEndListener);

        mRectYSpring =
                new SpringAnimation(this, RECT_Y)
                        .setSpring(
                                new SpringForce(endY)
                                        .setStiffness(mStiffnessY)
                                        .setDampingRatio(mDampingY)
                        )
                        .setStartValue(startY)
                        .setStartVelocity(yVelocityPxPerS)
                        .addEndListener(onYEndListener);

        float scaleVelocity = velocityPxPerMs.y * minVisibleChange;
        if (mCos) {
            scaleVelocity = Utilities.boundToRange(
                    -velocityPxPerMs.y * 1000 / mStartRect.height(), 0f, 8f);
            mCosStartTime = SystemClock.uptimeMillis();
        }
        float dampingZ = mRectDamping >= 0 ? mRectDamping
                : rp.getFloat(R.dimen.swipe_up_rect_scale_damping_ratio_v2);
        mRectScaleAnim =
                new SpringAnimation(this, RECT_SCALE_PROGRESS)
                        .setSpring(
                                new SpringForce(1f)
                                        .setStiffness(mRectStiffness)
                                        .setDampingRatio(dampingZ))
                        .setStartVelocity(scaleVelocity)
                        // ColorOS lets the size spring pass its target for the landing bounce.
                        .setMaxValue(mCos ? 1.25f : 1f)
                        .setMinimumVisibleChange(minVisibleChange)
                        .addEndListener((animation, canceled, value, velocity) -> {
                            if (mPaused) return;
                            mRectScaleAnimEnded = true;
                            maybeOnEnd();
                        });

        setCanRelease(false);
        mAnimsStarted = true;

        mRectXSpring.start();
        mRectYSpring.start();

        mRectScaleAnim.start();
    }

    public void end() {
        mPaused = false;
        if (mAnimsStarted) {
            if (mRectXSpring.canSkipToEnd()) {
                mRectXSpring.skipToEnd();
            }
            if (mRectYSpring.canSkipToEnd()) {
                mRectYSpring.skipToEnd();
            }
            if (mRectScaleAnim.canSkipToEnd()) {
                mRectScaleAnim.skipToEnd();
            }
            mCurrentScaleProgress = mRectScaleAnim.getSpring().getFinalPosition();

            // Ensures that we end the animation with the final values.
            mRectXAnimEnded = false;
            mRectYAnimEnded = false;
            mRectScaleAnimEnded = false;
            onUpdate();
        }

        mRectXAnimEnded = true;
        mRectYAnimEnded = true;
        mRectScaleAnimEnded = true;
        maybeOnEnd();
    }

    private boolean isEnded() {
        return mRectXAnimEnded && mRectYAnimEnded && mRectScaleAnimEnded;
    }

    private void onUpdate() {
        if (isEnded()) {
            // Prevent further updates from being called. This can happen between callbacks for
            // ending the x/y/scale animations.
            return;
        }

        if (!mOnUpdateListeners.isEmpty()) {
            float currentWidth = Utilities.mapRange(mCurrentScaleProgress, mStartRect.width(),
                    mTargetRect.width());
            float currentHeight = Utilities.mapRange(mCurrentScaleProgress, mStartRect.height(),
                    mTargetRect.height());
            if (mCos && mStartRect.width() > 0 && mTargetRect.width() > 0) {
                currentHeight = currentWidth * Utilities.mapRange(mCurrentScaleProgress,
                        mStartRect.height() / mStartRect.width(),
                        mTargetRect.height() / mTargetRect.width());
            }
            switch (mTracking) {
                case TRACKING_TOP:
                    mCurrentRect.set(mCurrentCenterX - currentWidth / 2,
                            mCurrentY,
                            mCurrentCenterX + currentWidth / 2,
                            mCurrentY + currentHeight);
                    break;
                case TRACKING_BOTTOM:
                    mCurrentRect.set(mCurrentCenterX - currentWidth / 2,
                            mCurrentY - currentHeight,
                            mCurrentCenterX + currentWidth / 2,
                            mCurrentY);
                    break;
                case TRACKING_CENTER:
                    mCurrentRect.set(mCurrentCenterX - currentWidth / 2,
                            mCurrentY - currentHeight / 2,
                            mCurrentCenterX + currentWidth / 2,
                            mCurrentY + currentHeight / 2);
                    break;
            }
            for (OnUpdateListener onUpdateListener : mOnUpdateListeners) {
                // Only the rect follows the bounce past the target; progress-driven effects
                // (alpha, corner radius, home play fraction) stay within [0, 1].
                onUpdateListener.onUpdate(mCurrentRect, Math.min(1f, mCurrentScaleProgress));
            }
        }
    }

    private void maybeOnEnd() {
        if (mAnimsStarted && isEnded()) {
            mAnimsStarted = false;
            setCanRelease(true);
            for (Animator.AnimatorListener animatorListener : mAnimatorListeners) {
                animatorListener.onAnimationEnd(null);
            }
        }
    }

    public void cancel() {
        if (mAnimsStarted) {
            for (OnUpdateListener onUpdateListener : mOnUpdateListeners) {
                onUpdateListener.onCancel();
            }
        }
        end();
    }

    /**
     * Modify the given velocity so that it's never below the minimum value, and falls off by the
     * given factor once it goes above the maximum value.
     * In order for the max soft cap to be enforced, the fall-off factor must be >1.
     */
    private static float adjustVelocity(float velocity, long min, long max, float factor) {
        float sign = Math.signum(velocity);
        float magnitude = Math.abs(velocity);

        // If the absolute velocity is less than the min, bump it up.
        if (magnitude < min) {
            return min * sign;
        }

        // If the absolute velocity falls between min and max, or the fall-off factor is invalid,
        // do nothing.
        if (magnitude <= max || factor <= 1) {
            return velocity;
        }

        // Scale the excess velocity by the fall-off factor.
        float excess = magnitude - max;
        float scaled = (float) Math.pow(excess, 1f / factor);
        return (max + scaled) * sign;
    }

    public interface OnUpdateListener {
        /**
         * Called when an update is made to the animation.
         * @param currentRect The rect of the window.
         * @param progress [0, 1] The progress of the rect scale animation.
         */
        void onUpdate(RectF currentRect, float progress);

        default void onCancel() { }
    }

    private abstract static class SpringConfig {
        protected RectF startRect;
        protected RectF targetRect;
        protected @Tracking int tracking;
        protected float stiffnessX;
        protected float stiffnessY;
        protected float dampingX;
        protected float dampingY;
        protected float rectStiffness;
        protected float rectDamping = -1f;
        protected boolean cos;
        protected float minVisChange;
        protected int maxVelocityPxPerS;

        private SpringConfig(Context context, RectF start, RectF target) {
            startRect = start;
            targetRect = target;

            ResourceProvider rp = DynamicResource.provider(context);
            minVisChange = rp.getDimension(R.dimen.swipe_up_fling_min_visible_change);
            maxVelocityPxPerS = (int) rp.getDimension(R.dimen.swipe_up_max_velocity);
        }
    }

    /**
     * Standard spring configuration parameters.
     */
    public static class DefaultSpringConfig extends SpringConfig {

        /** ColorOS lets the landing icon dip to about 95% of its size. */
        private static final float COS_CLOSE_BOUNCE_SIZE_FRACTION = 0.05f;

        public DefaultSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect) {
            super(context, startRect, targetRect);

            ResourceProvider rp = DynamicResource.provider(context);
            tracking = getDefaultTracking(deviceProfile);
            stiffnessX = rp.getFloat(R.dimen.swipe_up_rect_x_stiffness);
            stiffnessY = rp.getFloat(R.dimen.swipe_up_rect_y_stiffness);
            dampingX = rp.getFloat(R.dimen.swipe_up_rect_x_damping_ratio);
            dampingY = rp.getFloat(R.dimen.swipe_up_rect_y_damping_ratio);
            rectStiffness = rp.getFloat(R.dimen.swipe_up_rect_scale_stiffness_v2);
            if (QuickstepTransitionManager.cosExperience(context)) {
                // ColorOS 17 flies a closing window home at about 18 rad/s for size and x, y a
                // little softer, measured from its SurfaceFlinger layers. It lands on the icon
                // with a bounce of a fixed size: on the OP13 the icon passes about 6dp beyond its
                // spot and dips to about 95% of its size (damping 0.79 and 0.82 for the travel on
                // that phone). Derive the damping from the travel so that a longer flight on a
                // larger screen bounces by the same amount.
                stiffnessX = 330f;
                dampingX = 1f;
                stiffnessY = 250f;
                dampingY = cosBounceDamping(
                        rp.getDimension(R.dimen.cos_close_bounce_distance),
                        Math.abs(targetRect.centerY() - startRect.centerY()));
                rectStiffness = 330f;
                rectDamping = cosBounceDamping(
                        COS_CLOSE_BOUNCE_SIZE_FRACTION * targetRect.width(),
                        startRect.width() - targetRect.width());
                cos = true;
            }

            this.startRect = startRect;
            this.targetRect = targetRect;
        }

        /**
         * Damping ratio for which a spring released from rest overshoots its target by
         * {@code overshoot} after travelling {@code distance}.
         */
        private static float cosBounceDamping(float overshoot, float distance) {
            if (overshoot <= 0f || distance <= overshoot) return 1f;
            double l = Math.log(overshoot / distance);
            return Utilities.boundToRange(
                    (float) (-l / Math.sqrt(Math.PI * Math.PI + l * l)), 0.5f, 1f);
        }

        private @Tracking int getDefaultTracking(@Nullable DeviceProfile deviceProfile) {
            @Tracking int tracking;
            if (deviceProfile == null) {
                tracking = startRect.bottom < targetRect.bottom
                        ? TRACKING_BOTTOM
                        : TRACKING_TOP;
            } else {
                int heightPx = deviceProfile.getDeviceProperties().getHeightPx();
                Rect padding = deviceProfile.getWorkspaceProfile().getWorkspacePadding();

                final float topThreshold = heightPx / 3f;
                final float bottomThreshold = deviceProfile.getDeviceProperties().getHeightPx() - padding.bottom;

                if (targetRect.bottom > bottomThreshold) {
                    tracking = TRACKING_CENTER;
                } else if (targetRect.top < topThreshold) {
                    tracking = TRACKING_TOP;
                } else {
                    tracking = TRACKING_CENTER;
                }
            }
            return tracking;
        }
    }

    /**
     * Spring configuration parameters for widgets.
     */
    public static class WidgetSpringConfig extends DefaultSpringConfig {
        public WidgetSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF startRect, RectF targetRect) {
            super(context, deviceProfile, startRect, targetRect);
            ResourceProvider rp = DynamicResource.provider(context);
            stiffnessX = rp.getFloat(R.dimen.widget_x_stiffness);
            stiffnessY = rp.getFloat(R.dimen.widget_y_stiffness);
            rectStiffness = rp.getFloat(R.dimen.widget_rect_scale_stiffness);
        }
    }

    /**
     * Spring configuration parameters for Taskbar/Hotseat items on devices that have a taskbar.
     */
    public static class TaskbarHotseatSpringConfig extends DefaultSpringConfig {
        public TaskbarHotseatSpringConfig(Context context, DeviceProfile deviceProfile,
                RectF start, RectF target) {
            super(context, deviceProfile, start, target);
            tracking = TRACKING_CENTER;
        }
    }

}
