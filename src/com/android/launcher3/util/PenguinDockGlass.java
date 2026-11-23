/* Copyright (C) 2026 The PenguinOS Project. SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.util;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

/** A live, bounded frost with a quiet rim; never blurs the icon artwork. */
public final class PenguinDockGlass extends Drawable {
    /** Shared dock material in renderengine BlurFilter; opt-in, independent of COS style. */
    public static final int MATERIAL = 11;
    private static final int DOCK_BLUR_DP = 6;
    private static final int SEARCH_BLUR_DP = 16;
    /** Close to the glass tone over a blurred backdrop. */
    private static final int FLAT_FILL = 0x2E000000;
    private static final int FALLBACK_FILL = 0x50000000;
    private final View owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private BackgroundBlurDrawable blur;
    private int lastBlurRadius = -1;
    private int alpha = 255;
    private float flatProgress;
    /** Frames the plain fill stays after the glass turns live. */
    private int liveWarmupFrames;
    private final boolean searchPanel;
    private boolean capsule;
    /** Fixed corner radius in px, or -1 for the dock's. */
    private float cornerRadius = -1f;
    /** Blur radius in dp, or -1 for the dock's / Spotlight's. */
    private int blurDp = -1;
    /** Alpha of the owner and its parents, which the compositor blur does not see. */
    private float viewAlpha = 1f;
    private final ViewTreeObserver.OnPreDrawListener viewAlphaWatcher;
    public PenguinDockGlass(View owner) { this(owner, false); }
    public PenguinDockGlass(View owner, boolean searchPanel) {
        this.owner = owner;
        this.searchPanel = searchPanel;
        viewAlphaWatcher = () -> {
            float a = viewAlpha();
            if (a != viewAlpha) {
                viewAlpha = a;
                // A faded or hidden parent stops drawing the owner, so push the region's alpha
                // now; the compositor would otherwise keep showing the last blur.
                updateBlurNow();
                // The dock glass is drawn by hand rather than as a background, so redraw the owner.
                invalidateSelf();
                owner.invalidate();
            }
            return true;
        };
        // SurfaceFlinger draws the blur, so it has to fade with the views too
        owner.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnPreDrawListener(viewAlphaWatcher);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnPreDrawListener(viewAlphaWatcher);
            }
        });
        if (owner.isAttachedToWindow()) {
            owner.getViewTreeObserver().addOnPreDrawListener(viewAlphaWatcher);
        }
    }
    /** Draw from the blurred still wallpaper inside the view rather than the compositor. */
    private boolean wallpaperBackdrop;
    /** The backdrop was drawn last frame, so any compositor region is parked at alpha 0. */
    private boolean backdropActive;
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix backdropMatrix = new Matrix();
    private Bitmap backdropBitmap;
    private BitmapShader backdropShader;

    /**
     * Draws this glass from the blurred still wallpaper, as part of the view, as ColorOS does.
     * It then fades, scales and moves with its owner through every transition (the drawer,
     * recents, app launch and close), where the compositor glass has to be swapped for a flat
     * fill and back, which shows as a sudden change of blur. Only for surfaces that sit on the
     * wallpaper itself; a live wallpaper keeps the compositor glass.
     */
    public void setWallpaperBackdrop(boolean enabled) {
        if (wallpaperBackdrop == enabled) return;
        wallpaperBackdrop = enabled;
        invalidateSelf();
        owner.invalidate();
    }

    private boolean drawBackdrop(Canvas canvas, float radius, float density) {
        View root = owner.getRootView();
        if (root == null || root == owner || root.getWidth() <= 0) return false;
        int dp = blurDp >= 0 ? blurDp : searchPanel ? SEARCH_BLUR_DP : DOCK_BLUR_DP;
        Bitmap bitmap = com.android.launcher3.util.WallpaperBackdrop.get(owner.getContext())
                .getBitmap(owner, root.getWidth(), root.getHeight(), Math.round(dp * density));
        if (bitmap == null) return false;
        if (bitmap != backdropBitmap) {
            backdropBitmap = bitmap;
            backdropShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            backdropPaint.setShader(backdropShader);
        }
        // Sample where the owner rests on the screen (layout and scroll, no transforms), so the
        // glass travels with it as a part of it.
        float originX = 0, originY = 0;
        for (View v = owner; v != root; ) {
            if (!(v.getParent() instanceof View parent)) return false;
            originX += v.getLeft() - parent.getScrollX();
            originY += v.getTop() - parent.getScrollY();
            v = parent;
        }
        backdropMatrix.setScale(root.getWidth() / (float) bitmap.getWidth(),
                root.getHeight() / (float) bitmap.getHeight());
        backdropMatrix.postTranslate(-originX, -originY);
        backdropShader.setLocalMatrix(backdropMatrix);
        backdropPaint.setAlpha(alpha);
        rect.set(getBounds());
        canvas.drawRoundRect(rect, radius, radius, backdropPaint);
        return true;
    }

    @Override public void draw(Canvas canvas) {
        if (getBounds().isEmpty()) return;
        float density = owner.getResources().getDisplayMetrics().density;
        float radius = radius(density);
        if (wallpaperBackdrop && drawBackdrop(canvas, radius, density)) {
            backdropActive = true;
            if (blur != null) {
                // A region made before the backdrop was ready keeps its last state until drawn.
                blur.setAlpha(0);
                blur.setBounds(getBounds());
                blur.draw(canvas);
            }
            drawRim(canvas, radius, density, 1f);
            return;
        }
        backdropActive = false;
        WindowManager wm = owner.getContext().getSystemService(WindowManager.class);
        boolean enabled = wm != null && wm.isCrossWindowBlurEnabled();
        float live = liveWarmupFrames > 0 ? 0f : 1f - flatProgress;
        if (enabled && owner.getViewRootImpl() != null) {
            if (blur == null) blur = owner.getViewRootImpl().createBackgroundBlurDrawable();
            viewAlpha = viewAlpha();
            boolean changed = updateBlur(density);
            blur.setCornerRadius(radius);
            blur.setMaterial(MATERIAL);
            blur.setColor(0);
            blur.setBounds(getBounds());
            blur.draw(canvas);
            // The region goes to SurfaceFlinger on the next frame; changed inside a draw, a kept
            // region would wait for one that may never come, and stay hidden once flat.
            if (changed) owner.postInvalidateOnAnimation();
        }
        if (liveWarmupFrames > 0) {
            liveWarmupFrames--;
            owner.postInvalidateOnAnimation();
        }
        rect.set(getBounds());
        paint.setStyle(Paint.Style.FILL);
        // One shared home-dock material and radius for every glass surface. Without a live
        // blur, a plain fill stands in for the glass.
        boolean noGlass = !enabled || (blur == null && live > 0f);
        int fill = noGlass ? FALLBACK_FILL : FLAT_FILL;
        float fillAlpha = noGlass ? 1f : 1f - live;
        paint.setColor(fill);
        paint.setAlpha(Math.round(colorAlpha(fill, alpha) * fillAlpha));
        canvas.drawRoundRect(rect, radius, radius, paint);
        // Live iOS glass gets its lit rim from SurfaceFlinger, so the flat one only shows as
        // much as the plain fill stands in for it.
        float rimAlpha = !noGlass && LiquidGlass.isIosGlass(owner.getContext()) ? fillAlpha : 1f;
        drawRim(canvas, radius, density, rimAlpha);
    }

    private void drawRim(Canvas canvas, float radius, float density, float rimAlpha) {
        if (rimAlpha <= 0f) return;
        rect.set(getBounds());
        rect.inset(density * 0.25f, density * 0.25f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * 0.5f);
        paint.setColor(0x33FFFFFF);
        paint.setAlpha(Math.round(colorAlpha(0x33FFFFFF, alpha) * rimAlpha));
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
    /** Sets the region's blur radius and alpha; returns whether either changed. */
    private boolean updateBlur(float density) {
        if (backdropActive) {
            boolean changed = blur.getAlpha() != 0;
            blur.setAlpha(0);
            return changed;
        }
        float live = liveWarmupFrames > 0 ? 0f : 1f - flatProgress;
        // Keep a hidden region while flat, a re-added one is misplaced for a frame.
        boolean track = live == 0f && alpha > 0 && viewAlpha > 0f;
        // Spotlight is a large reading panel, so it needs a stronger frost than the dock.
        int dp = blurDp >= 0 ? blurDp : searchPanel ? SEARCH_BLUR_DP : DOCK_BLUR_DP;
        int blurRadius = track ? 1 : Math.round(dp * density);
        int blurAlpha = track ? 1 : Math.round(alpha * live * viewAlpha);
        boolean changed = blur.getAlpha() != blurAlpha || blurRadius != lastBlurRadius;
        lastBlurRadius = blurRadius;
        blur.setBlurRadius(blurRadius);
        blur.setAlpha(blurAlpha);
        return changed;
    }

    /**
     * The region's radius and alpha are copied for SurfaceFlinger before the frame is drawn,
     * but its position follows the views in that frame. Apply a change at once, so a dock
     * that starts to scale does not move the live glass for a frame.
     */
    private void updateBlurNow() {
        // Made during a draw, the change ships with the next frame, so make sure one comes.
        if (blur != null && updateBlur(owner.getResources().getDisplayMetrics().density)) {
            owner.postInvalidateOnAnimation();
        }
    }

    private float viewAlpha() {
        float a = 1f;
        View v = owner;
        while (v != null) {
            if (v.getVisibility() != View.VISIBLE) return 0f;
            a *= v.getAlpha();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return a;
    }

    /** Rounds the short sides fully, as iOS draws its small home pills. */
    public void setCapsule(boolean capsule) {
        if (this.capsule == capsule) return;
        this.capsule = capsule;
        invalidateSelf();
    }

    /** Uses a fixed corner radius in px, such as a folder's shape; -1 restores the dock's. */
    public void setCornerRadius(float radius) {
        if (cornerRadius == radius) return;
        cornerRadius = radius;
        invalidateSelf();
    }

    /** Sets the frost in dp for this surface; -1 restores the dock's. */
    public void setBlurDp(int dp) {
        if (blurDp == dp) return;
        blurDp = dp;
        updateBlurNow();
    }

    private float radius(float density) {
        if (cornerRadius >= 0f) return cornerRadius;
        return capsule ? getBounds().height() / 2f
                : Math.min(28 * density, getBounds().height() * 0.4f);
    }

    private static int colorAlpha(int color, int alpha) { return (color >>> 24) * alpha / 255; }

    /**
     * Replaces the live glass with a plain fill, from 0 (live) to 1 (plain). Use it while the
     * owner is blurred as a view: the compositor blur behind it would stay sharp-edged.
     */
    public void setFlatProgress(float progress) {
        progress = Math.max(0f, Math.min(1f, progress));
        if (flatProgress == progress) return;
        if (flatProgress == 1f) liveWarmupFrames = 2;
        flatProgress = progress;
        updateBlurNow();
        invalidateSelf();
        owner.invalidate();
    }
    @Override public void getOutline(android.graphics.Outline outline) {
        float density = owner.getResources().getDisplayMetrics().density;
        float radius = radius(density);
        outline.setRoundRect(getBounds(), radius);
        outline.setAlpha(alpha / 255f);
    }
    @Override public void setAlpha(int alpha) {
        this.alpha = alpha;
        updateBlurNow();
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
