/*
 * Blob shape, positions and blur from fairphone/fairphone-moments (LauncherBackground.kt,
 * Blob.kt). SPDX-FileCopyrightText: 2025. FairPhone B.V. SPDX-License-Identifier: EUPL-1.2
 */
package com.android.launcher3.moments;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.PathParser;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Fairphone's two blurred colour blobs over a plain background, or a flat gradient. */
public class MomentsBackgroundView extends View {

    private static final String BLOB = "M248.33,23.3C318.6,82.5 348.2,194.14 391.32,422.72"
            + "C392.27,435.59 392.2,448.48 392.19,461.38C392.19,462.59 392.19,463.81 392.19,465.07"
            + "C392.01,599.42 392.01,599.42 350.07,643.99C314.21,678.77 259.64,676.54 213.19,676.32"
            + "C209.27,676.31 205.34,676.31 201.42,676.32C158.88,676.36 115.03,676.01 76,657"
            + "C75.02,656.53 74.04,656.06 73.03,655.57C41.25,639.34 23.66,608.8 12.96,576.07"
            + "C6.11,554.12 2.68,530.89 1,508C0.91,506.87 0.82,505.74 0.73,504.58"
            + "C-0.21,490.94 -0.2,477.3 -0.19,463.63C-0.19,462.36 -0.19,461.1 -0.19,459.79"
            + "C-0.16,439.46 0.27,419.26 2,399C2.06,398.25 2.13,397.49 2.19,396.71"
            + "C9.58,308.84 26.97,219.96 120,39.15C121.84,37.18 123.54,35.14 125.25,33.06"
            + "C159.37,-5.75 208.93,-9.12 248.33,23.3Z";
    // Start and end x of each blob, and their y, in dp.
    private static final float LEFT_FROM = -436, LEFT_TO = -256, LEFT_Y = 326;
    private static final float RIGHT_FROM = 444, RIGHT_TO = 264, RIGHT_Y = 328;
    private static final long ENTER_MS = 800;
    private static final long ENTER_DELAY_MS = 100;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBlob = PathParser.createPathFromPathData(BLOB);
    private final Path mScratch = new Path();
    private final Matrix mMatrix = new Matrix();
    private int mColors = -1;
    private boolean mNight;
    private float mProgress = 1;
    // Below 1 in previews, which show the whole screen's background shrunk to fit.
    private float mScale = 1;
    private ValueAnimator mAnimator;

    public MomentsBackgroundView(Context context) {
        this(context, null);
    }

    public MomentsBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Animates the blobs in when the colours change, like Fairphone's home. */
    public void setColors(int colors, boolean night) {
        if (colors == mColors && night == mNight) {
            return;
        }
        boolean animate = colors != mColors;
        mColors = colors;
        mNight = night;
        updateBlur();
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        if (animate && !MomentsUi.isStatic(colors)) {
            mAnimator = ValueAnimator.ofFloat(0, 1).setDuration(ENTER_MS);
            mAnimator.setStartDelay(ENTER_DELAY_MS);
            mAnimator.setInterpolator(new PathInterpolator(0.33f, 1, 0.68f, 1));
            mAnimator.addUpdateListener(a -> {
                mProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            mProgress = 0;
            mAnimator.start();
        } else {
            mProgress = 1;
        }
        invalidate();
    }

    private void updateBlur() {
        float density = getResources().getDisplayMetrics().density;
        setRenderEffect(mColors < 0 || MomentsUi.isStatic(mColors) ? null
                : blur(density * mScale, mNight));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int screen = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        mScale = Math.min(1, (float) w / screen);
        updateBlur();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnimator != null) {
            mAnimator.end();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mColors >= 0) {
            canvas.save();
            canvas.scale(mScale, mScale);
            draw(canvas, mPaint, mBlob, mScratch, mMatrix, Math.round(getWidth() / mScale),
                    Math.round(getHeight() / mScale), getResources().getDisplayMetrics().density,
                    mColors, mNight, mProgress);
            canvas.restore();
        }
    }

    private static RenderEffect blur(float density, boolean night) {
        float radius = (night ? 200 : 100) * density;
        return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);
    }

    private static void draw(Canvas canvas, Paint paint, Path blob, Path scratch, Matrix matrix,
            int width, int height, float density, int colors, boolean night, float progress) {
        int left = MomentsUi.leftColor(colors);
        int right = MomentsUi.rightColor(colors);
        paint.setShader(null);
        paint.setAlpha(255);
        if (MomentsUi.isStatic(colors)) {
            paint.setShader(new LinearGradient(0, 0, 0, height, left, right,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            return;
        }
        canvas.drawColor(night ? 0xFF000000 : 0xFFCED3DC);
        float alpha = night ? 0.7f : 1f;
        drawBlob(canvas, paint, blob, scratch, matrix, density, left, alpha,
                LEFT_FROM + (LEFT_TO - LEFT_FROM) * progress, LEFT_Y);
        drawBlob(canvas, paint, blob, scratch, matrix, density, right, alpha,
                RIGHT_FROM + (RIGHT_TO - RIGHT_FROM) * progress, RIGHT_Y);
    }

    private static void drawBlob(Canvas canvas, Paint paint, Path blob, Path scratch,
            Matrix matrix, float density, int color, float alpha, float xDp, float yDp) {
        matrix.setScale(density, density);
        matrix.postTranslate(xDp * density, yDp * density);
        blob.transform(matrix, scratch);
        paint.setColor(color);
        paint.setAlpha(Math.round(((color >>> 24) & 0xFF) * alpha));
        canvas.drawPath(scratch, paint);
    }

    /** The background as a software bitmap, blur included, e.g. for the lock screen. */
    static Bitmap render(Context context, int colors, boolean night, int width, int height) {
        float density = context.getResources().getDisplayMetrics().density;
        RenderNode content = new RenderNode("MomentsBackground");
        content.setPosition(0, 0, width, height);
        if (!MomentsUi.isStatic(colors)) {
            content.setRenderEffect(blur(density, night));
        }
        RecordingCanvas canvas = content.beginRecording();
        draw(canvas, new Paint(Paint.ANTI_ALIAS_FLAG), PathParser.createPathFromPathData(BLOB),
                new Path(), new Matrix(), width, height, density, colors, night, 1);
        content.endRecording();
        RenderNode root = new RenderNode("MomentsBackgroundRoot");
        root.setPosition(0, 0, width, height);
        canvas = root.beginRecording();
        canvas.drawRenderNode(content);
        root.endRecording();
        Bitmap hardware = HardwareRenderer.createHardwareBitmap(root, width, height);
        return hardware == null ? null : hardware.copy(Bitmap.Config.ARGB_8888, false);
    }
}
