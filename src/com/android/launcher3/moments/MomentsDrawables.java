/*
 * Shapes from fairphone/fairphone-moments (CurrentModeButton.kt, CardWithAnimatedBorder.kt).
 * SPDX-FileCopyrightText: 2025. FairPhone B.V. SPDX-License-Identifier: EUPL-1.2
 */
package com.android.launcher3.moments;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

final class MomentsDrawables {

    private MomentsDrawables() {}

    private abstract static class Base extends Drawable {
        final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF mRect = new RectF();

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(ColorFilter colorFilter) { }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A filled rounded rectangle whose border fades from {@code top} to {@code bottom}. */
    static Drawable gradientBorder(int fill, int top, int bottom, float radius, float stroke) {
        return new Base() {
            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                mRect.set(b);
                mRect.inset(stroke / 2, stroke / 2);
                mPaint.setShader(null);
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(fill);
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(stroke);
                mPaint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                        top, bottom, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
            }
        };
    }

    /** A rounded bubble with a caret on top, pointing at what is above it. */
    static Drawable tooltip(int fill, float radius, float caret) {
        return new Base() {
            private final Path mPath = new Path();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                mRect.set(b.left, b.top + caret, b.right, b.bottom);
                mPaint.setColor(fill);
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
                mPath.reset();
                mPath.moveTo(b.centerX() - caret, b.top + caret);
                mPath.lineTo(b.centerX(), b.top);
                mPath.lineTo(b.centerX() + caret, b.top + caret);
                mPath.close();
                canvas.drawPath(mPath, mPaint);
            }

            @Override
            public boolean getPadding(Rect padding) {
                padding.set(0, Math.round(caret), 0, 0);
                return true;
            }
        };
    }

    /**
     * The active card's border: a sweep of the Moment's two colours that turns for two
     * seconds, then rests.
     */
    static Drawable animatedBorder(int fill, int[] colors, float radius, float border) {
        return new Base() {
            private final ValueAnimator mSpin = ValueAnimator.ofFloat(0, 360);
            private float mAngle;
            private final Matrix mMatrix = new Matrix();

            {
                mSpin.setDuration(2000);
                mSpin.setInterpolator(new LinearInterpolator());
                mSpin.addUpdateListener(a -> {
                    mAngle = (float) a.getAnimatedValue();
                    invalidateSelf();
                });
            }

            @Override
            public boolean setVisible(boolean visible, boolean restart) {
                boolean changed = super.setVisible(visible, restart);
                if (visible && !mSpin.isStarted()) {
                    mSpin.start();
                } else if (!visible) {
                    mSpin.end();
                }
                return changed;
            }

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                SweepGradient sweep = new SweepGradient(b.exactCenterX(), b.exactCenterY(),
                        new int[]{colors[0], colors[1], colors[0]}, null);
                mMatrix.setRotate(mAngle, b.exactCenterX(), b.exactCenterY());
                sweep.setLocalMatrix(mMatrix);
                mRect.set(b);
                mPaint.setShader(sweep);
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
                mPaint.setShader(null);
                mPaint.setColor(fill);
                mRect.inset(border, border);
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
            }
        };
    }
}
