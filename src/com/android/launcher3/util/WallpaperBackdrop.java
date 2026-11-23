/* Copyright (C) 2026 The PenguinOS Project. SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.util;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Small, pre-blurred copies of the static home wallpaper, as ColorOS draws its folder tiles and
 * dock glass.
 *
 * <p>A surface drawn from it is part of the view: it moves, scales and fades with its owner in
 * every launcher and app transition. A compositor blur region does not; it has to be hidden,
 * shown or swapped for a flat fill by hand around those transitions, which shows as surfaces
 * that vanish, pop back or change their blur abruptly.
 *
 * <p>One copy is kept per blur strength. Returns null for a live wallpaper, which has no still
 * image to blur; callers keep the compositor glass then.
 */
public final class WallpaperBackdrop {

    private static final String TAG = "WallpaperBackdrop";

    /** Most the copy is reduced; the blur hides the lost detail. */
    private static final int MAX_DOWNSCALE = 12;
    private static final int MIN_DOWNSCALE = 2;
    /** Box passes: three approximate a gaussian. */
    private static final int BOX_PASSES = 3;
    /**
     * The dock glass material in renderengine (BlurFilter, material 11): bright areas are
     * multiplied by #D4D4D4, dark and saturated ones kept.
     */
    private static final float MATERIAL_TINT = 0xD4 / 255f;

    private static WallpaperBackdrop sInstance;

    private static final class Entry {
        @Nullable Bitmap bitmap;
        boolean unavailable;
        int generation;
    }

    private final WallpaperManager mWallpaperManager;
    private final WeakHashMap<View, Boolean> mClients = new WeakHashMap<>();
    /** By blur radius in px, for the current screen size. */
    private final SparseArray<Entry> mEntries = new SparseArray<>();
    private int mWidth, mHeight;
    private int mGeneration;

    @MainThread
    public static WallpaperBackdrop get(Context context) {
        if (sInstance == null) sInstance = new WallpaperBackdrop(context.getApplicationContext());
        return sInstance;
    }

    private WallpaperBackdrop(Context context) {
        mWallpaperManager = WallpaperManager.getInstance(context);
        mWallpaperManager.addOnColorsChangedListener((colors, which) -> {
            if ((which & WallpaperManager.FLAG_SYSTEM) != 0) reloadAll();
        }, MAIN_EXECUTOR.getHandler());
    }

    /**
     * Returns the wallpaper blurred by {@code blurPx} for a screen of {@code width} x
     * {@code height}, to be drawn stretched over it, or null while it is made or when there is
     * none. {@code client} is redrawn once it is ready and whenever the wallpaper changes.
     */
    @MainThread
    @Nullable
    public Bitmap getBitmap(View client, int width, int height, int blurPx) {
        mClients.put(client, Boolean.TRUE);
        if (width <= 0 || height <= 0) return null;
        if (width != mWidth || height != mHeight) {
            mWidth = width;
            mHeight = height;
            mEntries.clear();
        }
        Entry entry = mEntries.get(blurPx);
        if (entry == null) {
            entry = new Entry();
            mEntries.put(blurPx, entry);
            load(blurPx, entry);
        }
        return entry.unavailable ? null : entry.bitmap;
    }

    @MainThread
    private void reloadAll() {
        mGeneration++;
        for (int i = 0; i < mEntries.size(); i++) {
            load(mEntries.keyAt(i), mEntries.valueAt(i));
        }
    }

    @MainThread
    private void load(int blurPx, Entry entry) {
        if (mWidth <= 0 || mHeight <= 0) return;
        final int generation = mGeneration;
        entry.generation = generation;
        final int width = mWidth, height = mHeight;
        UI_HELPER_EXECUTOR.execute(() -> {
            Bitmap result = null;
            try {
                result = create(width, height, blurPx);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Could not blur the wallpaper", e);
            }
            final Bitmap bitmap = result;
            MAIN_EXECUTOR.execute(() -> {
                if (entry.generation != generation || mEntries.get(blurPx) != entry
                        || width != mWidth || height != mHeight) {
                    return;
                }
                entry.bitmap = bitmap;
                entry.unavailable = bitmap == null;
                for (View v : new ArrayList<>(mClients.keySet())) {
                    if (v != null) v.invalidate();
                }
            });
        });
    }

    /** The compositor's sigma for a blur radius, as RenderEffect converts it. */
    private static double sigma(int blurPx) {
        return 0.57735 * blurPx + 0.5;
    }

    /** A light blur needs a finer copy, or it comes out far blurrier than asked. */
    private static int downscale(int blurPx) {
        return (int) Math.max(MIN_DOWNSCALE,
                Math.min(MAX_DOWNSCALE, Math.round(sigma(blurPx) / 1.5)));
    }

    @Nullable
    private Bitmap create(int width, int height, int blurPx) {
        if (mWallpaperManager.getWallpaperInfo() != null) return null;
        Drawable wallpaper = mWallpaperManager.getDrawable();
        if (wallpaper == null || wallpaper.getIntrinsicWidth() <= 0
                || wallpaper.getIntrinsicHeight() <= 0) {
            return null;
        }
        int downscale = downscale(blurPx);

        // Centre cropped to the screen, as the system shows it. Drawn at a third (or the
        // final size, if larger) first: one bilinear step to a twelfth would sparkle.
        int step = Math.min(3, downscale);
        int midW = Math.max(1, width / step), midH = Math.max(1, height / step);
        Bitmap mid = Bitmap.createBitmap(midW, midH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mid);
        float scale = Math.max((float) midW / wallpaper.getIntrinsicWidth(),
                (float) midH / wallpaper.getIntrinsicHeight());
        int w = Math.round(wallpaper.getIntrinsicWidth() * scale);
        int h = Math.round(wallpaper.getIntrinsicHeight() * scale);
        int left = (midW - w) / 2, top = (midH - h) / 2;
        wallpaper.setBounds(left, top, left + w, top + h);
        wallpaper.draw(canvas);

        int smallW = Math.max(1, width / downscale), smallH = Math.max(1, height / downscale);
        Bitmap small = Bitmap.createScaledBitmap(mid, smallW, smallH, true);
        if (small != mid) mid.recycle();
        if (!small.isMutable() || small.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap copy = small.copy(Bitmap.Config.ARGB_8888, true);
            small.recycle();
            small = copy;
        }

        int[] pixels = new int[smallW * smallH];
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
        int radius = boxRadius(blurPx, downscale);
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < BOX_PASSES; pass++) {
            boxBlur(pixels, scratch, smallW, smallH, radius, true);
            boxBlur(scratch, pixels, smallW, smallH, radius, false);
        }
        tint(pixels);
        small.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
        small.prepareToDraw();
        return small;
    }

    /** The box radius whose three passes match the compositor's blur at the reduced size. */
    private static int boxRadius(int blurPx, int downscale) {
        double s = sigma(blurPx) / downscale;
        // n passes of a box of width 2r+1 have variance n * ((2r+1)^2 - 1) / 12.
        double width = Math.sqrt(12.0 * s * s / BOX_PASSES + 1);
        return Math.max(1, (int) Math.round((width - 1) / 2));
    }

    /** One box pass along rows ({@code horizontal}) or columns, edges clamped. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int length = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        int span = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * w : line;
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[start + clamp(i, length) * step];
                a += c >>> 24; red += (c >> 16) & 0xff; g += (c >> 8) & 0xff; b += c & 0xff;
            }
            for (int i = 0; i < length; i++) {
                out[start + i * step] = ((a / span) << 24) | ((red / span) << 16)
                        | ((g / span) << 8) | (b / span);
                int add = in[start + clamp(i + r + 1, length) * step];
                int sub = in[start + clamp(i - r, length) * step];
                a += (add >>> 24) - (sub >>> 24);
                red += ((add >> 16) & 0xff) - ((sub >> 16) & 0xff);
                g += ((add >> 8) & 0xff) - ((sub >> 8) & 0xff);
                b += (add & 0xff) - (sub & 0xff);
            }
        }
    }

    private static int clamp(int i, int length) {
        return i < 0 ? 0 : i >= length ? length - 1 : i;
    }

    /** The material's tint: a neutral multiply that fades in with luminance. */
    private static void tint(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            float r = ((c >> 16) & 0xff) / 255f, g = ((c >> 8) & 0xff) / 255f, b = (c & 0xff) / 255f;
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float t = Math.max(0f, Math.min(1f, (lum - 0.2f) / 0.8f));
            float k = 1f - (1f - MATERIAL_TINT) * t * t * (3f - 2f * t);
            pixels[i] = 0xFF000000 | (Math.round(r * k * 255) << 16)
                    | (Math.round(g * k * 255) << 8) | Math.round(b * k * 255);
        }
    }
}
