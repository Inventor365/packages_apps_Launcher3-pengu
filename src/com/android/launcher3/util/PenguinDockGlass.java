/* Copyright (C) 2026 The PenguinOS Project. SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

/** A live, bounded frost with a quiet rim; never blurs the icon artwork. */
public final class PenguinDockGlass extends Drawable {
    /** Shared dock material in renderengine BlurFilter; opt-in, independent of COS style. */
    public static final int MATERIAL = 11;
    private final View owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private BackgroundBlurDrawable blur;
    private int alpha = 255;
    private final boolean searchPanel;
    public PenguinDockGlass(View owner) { this(owner, false); }
    public PenguinDockGlass(View owner, boolean searchPanel) {
        this.owner = owner;
        this.searchPanel = searchPanel;
    }
    @Override public void draw(Canvas canvas) {
        if (getBounds().isEmpty()) return;
        float density = owner.getResources().getDisplayMetrics().density;
        float radius = Math.min(28 * density, getBounds().height() * 0.4f);
        WindowManager wm = owner.getContext().getSystemService(WindowManager.class);
        boolean enabled = wm != null && wm.isCrossWindowBlurEnabled();
        if (enabled && owner.getViewRootImpl() != null) {
            if (blur == null) blur = owner.getViewRootImpl().createBackgroundBlurDrawable();
            blur.setBlurRadius(Math.round(6 * density));
            blur.setCornerRadius(radius);
            blur.setMaterial(MATERIAL);
            blur.setColor(0);
            blur.setBounds(getBounds());
            blur.setAlpha(alpha);
            blur.draw(canvas);
        }
        rect.set(getBounds());
        paint.setStyle(Paint.Style.FILL);
        // One shared home-dock material and radius for every glass surface.
        paint.setColor(enabled && blur != null
                ? android.graphics.Color.TRANSPARENT
                : 0x50000000);
        paint.setAlpha(colorAlpha(paint.getColor(), alpha));
        canvas.drawRoundRect(rect, radius, radius, paint);
        rect.inset(density * 0.25f, density * 0.25f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * 0.5f);
        paint.setColor(0x33FFFFFF);
        paint.setAlpha(colorAlpha(0x33FFFFFF, alpha));
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
    private static int colorAlpha(int color, int alpha) { return (color >>> 24) * alpha / 255; }
    @Override public void getOutline(android.graphics.Outline outline) {
        float density = owner.getResources().getDisplayMetrics().density;
        float radius = Math.min(28 * density, getBounds().height() * 0.4f);
        outline.setRoundRect(getBounds(), radius);
        outline.setAlpha(alpha / 255f);
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
