package com.android.launcher3.moments;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import com.android.launcher3.R;

public class HoldToExitButton extends TextView {

    private static final long HOLD_MS = 1500;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private final RectF mBounds = new RectF();
    private ValueAnimator mAnimator;
    private float mProgress;
    private Runnable mOnExit;

    public HoldToExitButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFill.setColor(context.getColor(R.color.moments_accent));
    }

    public void setOnExit(Runnable onExit) {
        mOnExit = onExit;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                animateTo(1f, (long) (HOLD_MS * (1 - mProgress)), new LinearInterpolator());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mProgress < 1f) {
                    animateTo(0f, 250, new DecelerateInterpolator());
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        // Accessibility services click instead of holding; let them through.
        super.performClick();
        fire();
        return true;
    }

    private void animateTo(float target, long duration, TimeInterpolator in) {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        mAnimator = ValueAnimator.ofFloat(mProgress, target);
        mAnimator.setDuration(duration);
        mAnimator.setInterpolator(in);
        mAnimator.addUpdateListener(a -> {
            mProgress = (float) a.getAnimatedValue();
            invalidate();
            if (mProgress >= 1f) {
                fire();
            }
        });
        mAnimator.start();
    }

    private void fire() {
        if (mAnimator != null) {
            mAnimator.removeAllUpdateListeners();
            mAnimator.cancel();
        }
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        if (mOnExit != null) {
            mOnExit.run();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mProgress > 0f) {
            mBounds.set(0, 0, getWidth(), getHeight());
            mClip.reset();
            mClip.addRoundRect(mBounds, getHeight() / 2f, getHeight() / 2f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClip);
            canvas.drawRect(0, 0, getWidth() * mProgress, getHeight(), mFill);
            canvas.restore();
        }
        super.onDraw(canvas);
    }
}
