/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.launcher3.folder;

import static com.android.app.animation.Interpolators.ACCELERATE_DECELERATE;
import static com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE;
import static com.android.launcher3.folder.ClippedFolderIconLayoutRule.ICON_OVERLAP_FACTOR;
import static com.android.launcher3.icons.GraphicsUtils.setColorAlphaBound;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.VisibleForTesting;

import com.android.launcher3.CellLayout;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Flags;
import com.android.launcher3.R;
import com.android.launcher3.celllayout.DelegatedCellDrawing;
import com.android.launcher3.graphics.PathWrapper;
import com.android.launcher3.graphics.ShapeDelegate;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.util.PenguinDockGlass;
import com.android.launcher3.util.WallpaperBackdrop;
import com.android.launcher3.util.LiquidGlass;
import com.android.launcher3.Utilities;
import com.android.launcher3.views.ActivityContext;

/**
 * This object represents a FolderIcon preview background. It stores drawing / measurement
 * information, handles drawing, and animation (accept state <--> rest state).
 */
public class PreviewBackground extends DelegatedCellDrawing {

    /** Frost of the home folder tiles; heavier than the dock, as on ColorOS 17. */
    private static final int FOLDER_GLASS_BLUR_DP = 20;

    private static final boolean DRAW_SHADOW = false;
    private static final boolean DRAW_STROKE = false;

    @VisibleForTesting protected static final int CONSUMPTION_ANIMATION_DURATION = 100;

    @VisibleForTesting protected static final float HOVER_SCALE = 1.1f;
    @VisibleForTesting protected static final int HOVER_ANIMATION_DURATION = 300;

    private final Context mContext;
    private final PorterDuffXfermode mShadowPorterDuffXfermode
            = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
    private RadialGradient mShadowShader = null;

    private final Matrix mShaderMatrix = new Matrix();
    private final PathWrapper mPath = new PathWrapper();
    private final Rect mBackgroundBounds = new Rect();
    private final Rect mTargetBackgroundBounds = new Rect();
    private final RectF mScaledBackgroundBounds = new RectF();

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    float mScale = 1f;
    private int mBgColor;
    private int mStrokeColor;
    private float mStrokeWidth;
    private int mStrokeAlpha = MAX_BG_OPACITY;
    private int mShadowAlpha = 255;
    private View mInvalidateDelegate;

    int previewSize;
    private int mSpanX = 1;
    private int mSpanY = 1;

    private CellLayout mDrawingDelegate;

    // When the PreviewBackground is drawn under an icon (for creating a folder) the border
    // should not occlude the icon
    public boolean isClipping = true;

    // Drawing / animation configurations
    @VisibleForTesting protected static final float ACCEPT_SCALE_FACTOR = 1.20f;

    // Expressed on a scale from 0 to 255.
    private static final int BG_OPACITY = 255;
    private static final int MAX_BG_OPACITY = 255;
    private static final int SHADOW_OPACITY = 40;

    @VisibleForTesting protected ValueAnimator mScaleAnimator;
    private ObjectAnimator mStrokeAlphaAnimator;
    private ObjectAnimator mShadowAnimator;
    private ValueAnimator mBoundsAnimator;

    @VisibleForTesting protected boolean mIsAccepting;
    @VisibleForTesting protected boolean mIsHovered;
    @VisibleForTesting protected boolean mIsHoveredOrAnimating;

    private static final Property<PreviewBackground, Integer> STROKE_ALPHA =
            new Property<PreviewBackground, Integer>(Integer.class, "strokeAlpha") {
                @Override
                public Integer get(PreviewBackground previewBackground) {
                    return previewBackground.mStrokeAlpha;
                }

                @Override
                public void set(PreviewBackground previewBackground, Integer alpha) {
                    previewBackground.mStrokeAlpha = alpha;
                    previewBackground.invalidate();
                }
            };

    private static final Property<PreviewBackground, Integer> SHADOW_ALPHA =
            new Property<PreviewBackground, Integer>(Integer.class, "shadowAlpha") {
                @Override
                public Integer get(PreviewBackground previewBackground) {
                    return previewBackground.mShadowAlpha;
                }

                @Override
                public void set(PreviewBackground previewBackground, Integer alpha) {
                    previewBackground.mShadowAlpha = alpha;
                    previewBackground.invalidate();
                }
            };

    public static void calculateBackgroundBounds(
            DeviceProfile grid,
            int availableSpaceX,
            int availableSpaceY,
            int topPadding,
            int spanX,
            int spanY,
            boolean showLabels,
            Rect outBounds) {
        int previewSize = grid.getFolderProfile().getFolderIconSizePx();
        Point borderSpace = grid.getWorkspaceProfile().getCellLayoutBorderSpacePx();
        Point cellSize = grid.getWorkspaceProfile().getCellSize();

        int cellWidth = availableSpaceX > 0
                ? (spanX > 1 ? (availableSpaceX - (spanX - 1) * borderSpace.x) / spanX
                        : availableSpaceX)
                : (cellSize.x > 0 ? cellSize.x : grid.getWorkspaceProfile().getCellWidthPx());
        int cellHeight = availableSpaceY > 0
                ? (spanY > 1 ? (availableSpaceY - (spanY - 1) * borderSpace.y) / spanY
                        : availableSpaceY)
                : (cellSize.y > 0 ? cellSize.y : grid.getWorkspaceProfile().getCellHeightPx());

        int backgroundWidth;
        int backgroundHeight;
        int backgroundLeft;
        int backgroundTop;

        if (topPadding <= 0) {
            int cellPaddingY = grid.getWorkspaceProfile().getCellYPaddingPx();
            if (cellPaddingY < 0) {
                int cHeight = grid.getWorkspaceProfile().getCellHeightPx();
                cellPaddingY = Math.max(0, (cellHeight - cHeight) / 2);
            }
            topPadding = cellPaddingY;
        }

        if (spanX == 1 && spanY == 1) {
            backgroundWidth = previewSize;
            backgroundHeight = previewSize;
            backgroundLeft = availableSpaceX > 0
                    ? (availableSpaceX - backgroundWidth) / 2
                    : (cellWidth - previewSize) / 2;
            backgroundTop = topPadding + grid.getFolderProfile().getFolderIconOffsetYPx();
        } else {
            backgroundHeight = (spanY - 1) * (cellHeight + borderSpace.y) + previewSize;
            // Placed by its rows, as the icons around it are: the cell container pads a folder
            // as if one icon were centred in its whole height, which hung a multi-row panel
            // hundreds of pixels below its rows (and away from the drop outline).
            int rowPadding = grid.getWorkspaceProfile().getCellYPaddingPx();
            if (rowPadding < 0) {
                rowPadding = Math.max(0,
                        (cellHeight - grid.getWorkspaceProfile().getCellHeightPx()) / 2);
            }
            backgroundTop = rowPadding + grid.getFolderProfile().getFolderIconOffsetYPx();
            if (!showLabels) {
                // Without labels an icon is centred in its cell, so the row's icons are too.
                backgroundTop = Math.max(0, (cellHeight - previewSize) / 2);
            }
            // Middle of the icons of the first and last rows.
            float rowsMiddle = backgroundTop + previewSize / 2f
                    + (spanY - 1) * (cellHeight + borderSpace.y) / 2f;

            if (spanX == 1) {
                backgroundWidth = previewSize;
                backgroundLeft = availableSpaceX > 0
                        ? (availableSpaceX - backgroundWidth) / 2
                        : (cellWidth - previewSize) / 2;
            } else {
                int numColumns = grid.inv != null ? grid.inv.numColumns : 4;
                float bgPaddingHorizDp = numColumns <= 3 ? 18f : (numColumns == 4 ? 10f : 4.7f);
                int oosBgPaddingHoriz = Utilities.dpToPx(bgPaddingHorizDp);
                int iconAlignedPaddingHoriz = Math.max(0, (cellWidth - previewSize) / 2);
                int bgPaddingHoriz = Math.min(oosBgPaddingHoriz, iconAlignedPaddingHoriz);
                int totalSpanWidth = availableSpaceX > 0
                        ? availableSpaceX
                        : (spanX * cellWidth + (spanX - 1) * borderSpace.x);
                backgroundLeft = bgPaddingHoriz;
                backgroundWidth = Math.max(previewSize, totalSpanWidth - 2 * bgPaddingHoriz);
            }
            // Tall grid cells would stretch the panel far past its width (a 2x2 folder drawn
            // as a tall slab with a gap between its rows), so keep each cell of it square.
            int maxHeight = Math.max(previewSize, Math.round(backgroundWidth * spanY / (float) spanX));
            backgroundHeight = Math.min(backgroundHeight, maxHeight);
            if (spanY > 1) {
                backgroundTop = Math.max(0, Math.round(rowsMiddle - backgroundHeight / 2f));
            }

            // A one-row or one-column folder is a capsule in ColorOS's proportions: about 2.6
            // times as long as it is thick, thicker than an icon when the cells allow it. Sized
            // by the icon alone it reads as a thin strip on grids of five or more columns. It
            // grows evenly about the icon's centre, so its icons stay in line with the row.
            if (spanY == 1 && spanX > 1) {
                int thick = clampPillThickness(Math.round(backgroundWidth / PILL_ASPECT),
                        previewSize, cellHeight);
                int top = backgroundTop - (thick - backgroundHeight) / 2;
                backgroundTop = Math.max(0, top);
                backgroundHeight = thick;
            } else if (spanX == 1 && spanY > 1) {
                // The same capsule as the horizontal folder of as many cells, stood upright, so
                // the two orientations read as one shape. Centred between the first and last
                // row's icons, as the row's icons are centred in their cells.
                int numColumns = grid.inv != null ? grid.inv.numColumns : 4;
                float padDp = numColumns <= 3 ? 18f : (numColumns == 4 ? 10f : 4.7f);
                int padHoriz = Math.min(Utilities.dpToPx(padDp),
                        Math.max(0, (cellWidth - previewSize) / 2));
                int length = spanY * cellWidth + (spanY - 1) * borderSpace.x - 2 * padHoriz;
                int thick = clampPillThickness(Math.round(length / PILL_ASPECT), previewSize,
                        cellHeight);
                thick = Math.min(thick, Math.max(previewSize, cellWidth - 2 * padHoriz));
                int spanHeight = spanY * cellHeight + (spanY - 1) * borderSpace.y;
                length = Math.max(thick, Math.min(length, spanHeight));
                backgroundTop = Math.max(0, Math.round(rowsMiddle - length / 2f));
                backgroundHeight = length;
                backgroundLeft = (cellWidth - thick) / 2;
                backgroundWidth = thick;
            }
        }

        outBounds.set(
            backgroundLeft,
            backgroundTop,
            backgroundLeft + backgroundWidth,
            backgroundTop + backgroundHeight);
    }

    /** Length over thickness of a one-row or one-column folder, as ColorOS draws it. */
    private static final float PILL_ASPECT = 2.6f;
    /** The thickest a capsule folder gets, against the icon size. */
    private static final float PILL_MAX_ICON_RATIO = 1.3f;

    private static int clampPillThickness(int wanted, int previewSize, int cellSpace) {
        int max = Math.min(Math.round(previewSize * PILL_MAX_ICON_RATIO), cellSpace);
        return Math.max(previewSize, Math.min(wanted, max));
    }

    public PreviewBackground(Context context) {
        mContext = context;
    }

    /**
     * Draws folder background under cell layout
     */
    @Override
    public void drawUnderItem(Canvas canvas) {
        drawBackground(canvas);
        if (!isClipping) {
            drawBackgroundStroke(canvas);
        }
    }

    /**
     * Draws folder background on cell layout
     */
    @Override
    public void drawOverItem(Canvas canvas) {
        if (isClipping) {
            drawBackgroundStroke(canvas);
        }
    }

    public void setup(Context context, ActivityContext activity, View invalidateDelegate,
            int availableSpaceX, int availableSpaceY, int topPadding, int spanX, int spanY) {
        mInvalidateDelegate = invalidateDelegate;
        mSpanX = spanX;
        mSpanY = spanY;

        TypedArray ta = context.getTheme().obtainStyledAttributes(R.styleable.FolderIconPreview);
        mStrokeColor = ta.getColor(R.styleable.FolderIconPreview_folderIconBorderColor, 0);
        mBgColor = ta.getColor(R.styleable.FolderIconPreview_folderPreviewColor, 0);
        ta.recycle();

        DeviceProfile grid = activity.getDeviceProfile();
        previewSize = grid.getFolderProfile().getFolderIconSizePx();

        calculateBackgroundBounds(
            grid,
            availableSpaceX,
            availableSpaceY,
            topPadding,
            spanX,
            spanY,
            LauncherPrefs.SHOW_DESKTOP_LABELS.get(context),
            mBackgroundBounds);
        if (mMaxBottom >= 0 && mBackgroundBounds.bottom > mMaxBottom
                && (spanX > 1 || spanY > 1)) {
            // Keep a multi-cell panel clear of the Search pill above the dock.
            mBackgroundBounds.bottom = Math.max(mBackgroundBounds.top + previewSize, mMaxBottom);
        }
        mTargetBackgroundBounds.set(mBackgroundBounds);

        // Stroke width is 1dp
        mStrokeWidth = context.getResources().getDisplayMetrics().density;

        if (DRAW_SHADOW) {
            float radius = getScaledRadius();
            float shadowRadius = radius + mStrokeWidth;
            int shadowColor = Color.argb(SHADOW_OPACITY, 0, 0, 0);
            mShadowShader = new RadialGradient(0, 0, 1,
                    new int[]{shadowColor, Color.TRANSPARENT},
                    new float[]{radius / shadowRadius, 1},
                    Shader.TileMode.CLAMP);
        }

        invalidate();
    }

    void getBounds(Rect outBounds) {
        outBounds.set(mBackgroundBounds);
    }

    void getTargetBounds(Rect outBounds) {
        outBounds.set(mTargetBackgroundBounds);
    }

    int getTargetPreviewLeft() {
        return mTargetBackgroundBounds.centerX() - previewSize / 2;
    }

    int getTargetPreviewTop() {
        return mTargetBackgroundBounds.centerY() - previewSize / 2;
    }

    void animateBoundsFrom(Rect startBounds, long duration) {
        if (mBoundsAnimator != null) {
            mBoundsAnimator.cancel();
        }

        Rect endBounds = new Rect(mBackgroundBounds);
        if (startBounds.equals(endBounds)) return;

        ValueAnimator animator = ValueAnimator.ofObject(
                new RectEvaluator(new Rect()),
                new Rect(startBounds),
                endBounds);
        mBoundsAnimator = animator;
        mBackgroundBounds.set(startBounds);

        animator.addUpdateListener(animation -> {
            mBackgroundBounds.set(
                    (Rect) animation.getAnimatedValue());
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mBoundsAnimator == animation) {
                    mBoundsAnimator = null;
                }
            }
        });
        animator.setDuration(duration);
        animator.start();
    }

    void getScaledBounds(RectF outBounds) {
        outBounds.set(getBoundsAtScale(mScale));
    }

    private RectF getBoundsAtScale(float scale) {
        float centerX = mBackgroundBounds.exactCenterX();
        float centerY = mBackgroundBounds.exactCenterY();
        float halfWidth = mBackgroundBounds.width() * scale / 2f;
        float halfHeight = mBackgroundBounds.height() * scale / 2f;

        mScaledBackgroundBounds.set(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight);

        return mScaledBackgroundBounds;
    }

    public int getRadius() {
        return Math.min(mBackgroundBounds.width(), mBackgroundBounds.height()) / 2;
    }

    int getScaledRadius() {
        return (int) (mScale * getRadius());
    }

    int getOffsetX() {
        return mBackgroundBounds.left - (getScaledRadius() - getRadius());
    }

    int getOffsetY() {
        return mBackgroundBounds.top - (getScaledRadius() - getRadius());
    }

    int getPreviewLeft() {
        return mBackgroundBounds.centerX() - previewSize / 2;
    }

    int getPreviewTop() {
        return mBackgroundBounds.centerY() - previewSize / 2;
    }

    /**
     * Returns the progress of the scale animation to accept state, where 0 means the scale is at
     * 1f and 1 means the scale is at ACCEPT_SCALE_FACTOR. Returns 0 when scaled due to hover.
     */
    float getAcceptScaleProgress() {
        return mIsHoveredOrAnimating ? 0 : (mScale - 1f) / (ACCEPT_SCALE_FACTOR - 1f);
    }

    void invalidate() {
        if (mInvalidateDelegate != null) {
            mInvalidateDelegate.invalidate();
        }

        if (mDrawingDelegate != null) {
            mDrawingDelegate.invalidate();
        }
    }

    void setInvalidateDelegate(View invalidateDelegate) {
        mInvalidateDelegate = invalidateDelegate;
        invalidate();
    }

    public int getBgColor() {
        return mBgColor;
    }

    boolean isBoundsAnimating() {
        return mBoundsAnimator != null;
    }

    public void drawBackground(Canvas canvas) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(getBgColor());

        RectF bounds = getBoundsAtScale(mScale);
        if (!drawGlassTile(canvas, bounds)) {
            drawShapeInBounds(canvas, bounds, mScale, mPaint);
        }
        drawShadow(canvas);
        if (LiquidGlass.isEnabled(mContext)) {
            mGlassBounds.set(bounds);
            getDrawnShapePath(mGlassPath);
            LiquidGlass.drawRim(canvas, mGlassBounds, getDrawnCornerRadius(), mGlassPath,
                    mContext.getResources().getDisplayMetrics().density, 0.8f);
        }
    }

    private final Path mGlassPath = new Path();
    private final RectF mGlassBounds = new RectF();
    private final Rect mGlassTileBounds = new Rect();
    private PenguinDockGlass mGlassTile;
    private boolean mGlassVisible = true;
    private int mMaxBottom = -1;

    /**
     * Shows or hides the frosted tile. Its blur is drawn by the compositor under the window, so a
     * folder the launcher no longer shows (behind the drawer, in recents, or open) has to drop
     * it explicitly; covering or not drawing the view leaves the blur in place.
     */
    void setGlassVisible(boolean visible) {
        if (mGlassVisible == visible) return;
        mGlassVisible = visible;
        if (mGlassTile != null) mGlassTile.setAlpha(visible && !mDrewBackdrop ? 255 : 0);
        invalidate();
    }

    /** Lowest y the panel of a multi-cell folder may reach, or -1 for no limit. */
    void setMaxBottom(int maxBottom) {
        mMaxBottom = maxBottom;
    }

    /**
     * Home and dock folders sit on the wallpaper as a frosted tile, as ColorOS 17 draws them:
     * the same compositor blur as the dock glass, in the folder's own shape. Returns false where
     * the plain filled shape is drawn instead.
     */
    /** Whether this folder draws as a frosted glass tile rather than a filled shape. */
    boolean drawsGlassTile() {
        return LauncherPrefs.FOLDER_BLUR.get(mContext)
                && mInvalidateDelegate instanceof FolderIcon icon && icon.mInfo != null
                && !icon.mInfo.forceBigPreview
                && (icon.mInfo.container == LauncherSettings.Favorites.CONTAINER_DESKTOP
                        || icon.mInfo.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT);
    }

    private boolean drawGlassTile(Canvas canvas, RectF bounds) {
        if (!drawsGlassTile()) return false;
        FolderIcon icon = (FolderIcon) mInvalidateDelegate;
        if (drawBackdropTile(canvas, bounds, icon)) {
            if (!mDrewBackdrop) {
                mDrewBackdrop = true;
                // Took over from the compositor glass: drop its region.
                if (mGlassTile != null) mGlassTile.setAlpha(0);
            }
            // A glass region drawn before keeps its last state until drawn again.
            if (mGlassTile != null) {
                mGlassTile.setBounds(mGlassTileBounds);
                mGlassTile.draw(canvas);
            }
            return true;
        }
        if (mDrewBackdrop) {
            mDrewBackdrop = false;
            if (mGlassTile != null) mGlassTile.setAlpha(mGlassVisible ? 255 : 0);
        }
        // A live wallpaper has no still image: the compositor blurs it under the window.
        if (mGlassTile == null) {
            mGlassTile = new PenguinDockGlass(icon);
            mGlassTile.setBlurDp(FOLDER_GLASS_BLUR_DP);
            mGlassTile.setAlpha(mGlassVisible ? 255 : 0);
        }
        bounds.round(mGlassTileBounds);
        mGlassTile.setBounds(mGlassTileBounds);
        mGlassTile.setCornerRadius(getDrawnCornerRadius());
        mGlassTile.draw(canvas);
        return true;
    }

    private final Paint mBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mBackdropMatrix = new Matrix();
    private final RectF mRimBounds = new RectF();
    private Bitmap mBackdropBitmap;
    private BitmapShader mBackdropShader;
    private boolean mDrewBackdrop;

    /**
     * Draws the tile from the blurred still wallpaper, as ColorOS does. It is part of the view,
     * so it follows the icon through every transition: the drawer, recents, app launch and
     * close, folder open and close, and drags. Returns false when there is no still wallpaper.
     */
    private boolean drawBackdropTile(Canvas canvas, RectF bounds, FolderIcon icon) {
        View root = icon.getRootView();
        if (root == null || root == icon) return false;
        float density = mContext.getResources().getDisplayMetrics().density;
        Bitmap bitmap = WallpaperBackdrop.get(mContext).getBitmap(icon, root.getWidth(),
                root.getHeight(), Math.round(FOLDER_GLASS_BLUR_DP * density));
        if (bitmap == null) return false;
        if (bitmap != mBackdropBitmap) {
            mBackdropBitmap = bitmap;
            mBackdropShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            mBackdropPaint.setShader(mBackdropShader);
        }
        // Sample where the folder rests on the screen (layout and scroll, no transforms): the
        // tile then moves and scales with the icon in transitions, as a part of it.
        float originX = 0, originY = 0;
        for (View v = icon; v != root; ) {
            if (!(v.getParent() instanceof View parent)) return false;
            originX += v.getLeft() - parent.getScrollX();
            originY += v.getTop() - parent.getScrollY();
            v = parent;
        }
        mBackdropMatrix.setScale(root.getWidth() / (float) bitmap.getWidth(),
                root.getHeight() / (float) bitmap.getHeight());
        mBackdropMatrix.postTranslate(-originX, -originY);
        mBackdropShader.setLocalMatrix(mBackdropMatrix);
        drawShapeInBounds(canvas, bounds, mScale, mBackdropPaint);

        // The quiet rim of the dock glass.
        mRimBounds.set(bounds);
        mRimBounds.inset(density * 0.25f, density * 0.25f);
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(density * 0.5f);
        mRimPaint.setColor(0x33FFFFFF);
        drawShapeInBounds(canvas, mRimBounds, mScale, mRimPaint);
        return true;
    }

    private ShapeDelegate getShape() {
        return ThemeManager.INSTANCE.get(mContext).getFolderShape();
    }

    private float getCornerRadius(
            ShapeDelegate shape,
            RectF bounds,
            float scale) {
        if (!(shape instanceof ShapeDelegate.RoundedSquare roundedSquare)) {
            return 0f;
        }

        if ((mSpanX == 2 && mSpanY == 1) || (mSpanX == 1 && mSpanY == 2)) {
            return Math.min(bounds.width(), bounds.height()) / 2f * scale;
        }

        if (mSpanX > 1 || mSpanY > 1) {
            float boxyRadius = previewSize * 0.44f * scale;
            if (shape instanceof ShapeDelegate.RoundedSquare
                    && !(shape instanceof ShapeDelegate.Circle)) {
                boxyRadius = Math.max(boxyRadius,
                        previewSize / 2f * roundedSquare.getRadiusRatio() * scale);
            }
            return Math.min(boxyRadius, Math.min(bounds.width(), bounds.height()) / 2f);
        }

        if (shape instanceof ShapeDelegate.Circle) {
            return Math.min(bounds.width(), bounds.height()) / 2f;
        }

        float fixedRadius =
                previewSize / 2f * roundedSquare.getRadiusRatio() * scale;
        return Math.min(
                fixedRadius,
                Math.min(bounds.width(), bounds.height()) / 2f);
    }

    private void drawShapeInBounds(
            Canvas canvas,
            RectF bounds,
            float scale,
            Paint paint) {
        ShapeDelegate shape = getShape();

        if (shape instanceof ShapeDelegate.RoundedSquare) {
            float radius = getCornerRadius(shape, bounds, scale);
            canvas.drawRoundRect(bounds, radius, radius, paint);
        } else {
            shape.drawShapeInBounds(canvas, bounds, paint);
        }
    }

    private void addShapeToPathInBounds(
            Path path,
            RectF bounds,
            float scale) {
        ShapeDelegate shape = getShape();

        if (shape instanceof ShapeDelegate.RoundedSquare) {
            float radius = getCornerRadius(shape, bounds, scale);
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        } else {
            shape.addToPathInBounds(path, bounds);
        }
    }

    float getDrawnCornerRadius() {
        return getCornerRadius(
                getShape(),
                getBoundsAtScale(mScale),
                mScale);
    }

    public void drawShadow(Canvas canvas) {
        if (!DRAW_SHADOW) {
            return;
        }
        if (mShadowShader == null) {
            return;
        }

        float radius = getScaledRadius();
        float shadowRadius = radius + mStrokeWidth;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(Color.BLACK);
        int offsetX = getOffsetX();
        int offsetY = getOffsetY();
        final int saveCount;
        if (canvas.isHardwareAccelerated()) {
            saveCount = canvas.saveLayer(offsetX - mStrokeWidth, offsetY,
                    offsetX + radius + shadowRadius, offsetY + shadowRadius + shadowRadius, null);

        } else {
            saveCount = canvas.save();
            canvas.clipPath(getClipPath().getPath(), Region.Op.DIFFERENCE);
        }

        mShaderMatrix.setScale(shadowRadius, shadowRadius);
        mShaderMatrix.postTranslate(radius + offsetX, shadowRadius + offsetY);
        mShadowShader.setLocalMatrix(mShaderMatrix);
        mPaint.setAlpha(mShadowAlpha);
        mPaint.setShader(mShadowShader);
        canvas.drawPaint(mPaint);
        mPaint.setAlpha(255);
        mPaint.setShader(null);
        if (canvas.isHardwareAccelerated()) {
            mPaint.setXfermode(mShadowPorterDuffXfermode);
            getShape().drawShape(canvas, offsetX, offsetY, radius, mPaint);
            mPaint.setXfermode(null);
        }

        canvas.restoreToCount(saveCount);
    }

    public void fadeInBackgroundShadow() {
        if (!DRAW_SHADOW) {
            return;
        }
        if (mShadowAnimator != null) {
            mShadowAnimator.cancel();
        }
        mShadowAnimator = ObjectAnimator
                .ofInt(this, SHADOW_ALPHA, 0, 255)
                .setDuration(100);
        mShadowAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mShadowAnimator = null;
            }
        });
        mShadowAnimator.start();
    }

    public void animateBackgroundStroke() {
        if (!DRAW_STROKE) {
            return;
        }

        if (mStrokeAlphaAnimator != null) {
            mStrokeAlphaAnimator.cancel();
        }
        mStrokeAlphaAnimator = ObjectAnimator
                .ofInt(this, STROKE_ALPHA, MAX_BG_OPACITY / 2, MAX_BG_OPACITY)
                .setDuration(100);
        mStrokeAlphaAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mStrokeAlphaAnimator = null;
            }
        });
        mStrokeAlphaAnimator.start();
    }

    public void drawBackgroundStroke(Canvas canvas) {
        if (!DRAW_STROKE) {
            return;
        }
        mPaint.setColor(setColorAlphaBound(mStrokeColor, mStrokeAlpha));
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(mStrokeWidth);

        RectF bounds = getBoundsAtScale(mScale);
        bounds.inset(1f, 1f);
        drawShapeInBounds(canvas, bounds, mScale, mPaint);
    }

    /**
     * Draws the leave-behind shape on the given canvas and in the given color.
     */
    public void drawLeaveBehind(Canvas canvas, int color) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);

        RectF bounds = getBoundsAtScale(0.5f);
        drawShapeInBounds(canvas, bounds, 0.5f, mPaint);
    }

    public PathWrapper getClipPath() {
        mPath.reset();
        float scale = mScale;

        if (!Flags.enableLauncherIconShapes()) {
            scale *= ICON_OVERLAP_FACTOR;
        }

        RectF bounds = getBoundsAtScale(scale);
        addShapeToPathInBounds(mPath.getPath(), bounds, scale);
        mPath.getBounds().set(bounds);
        mPath.setCornerRadius(getCornerRadius(getShape(), bounds, scale));
        return mPath;
    }

    public void getDrawnShapePath(Path out) {
        out.reset();
        addShapeToPathInBounds(out, getBoundsAtScale(mScale), mScale);
    }

    private void delegateDrawing(CellLayout delegate, int cellX, int cellY) {
        if (mDrawingDelegate != delegate) {
            delegate.addDelegatedCellDrawing(this);
        }

        mDrawingDelegate = delegate;
        mDelegateCellX = cellX;
        mDelegateCellY = cellY;

        invalidate();
    }

    private void clearDrawingDelegate() {
        if (mDrawingDelegate != null) {
            mDrawingDelegate.removeDelegatedCellDrawing(this);
        }

        mDrawingDelegate = null;
        isClipping = false;
        invalidate();
    }

    boolean drawingDelegated() {
        return mDrawingDelegate != null;
    }

    protected void animateScale(boolean isAccepting, boolean isHovered) {
        if (mScaleAnimator != null) {
            mScaleAnimator.cancel();
        }

        final float startScale = mScale;
        final float endScale = isAccepting ? ACCEPT_SCALE_FACTOR : (isHovered ? HOVER_SCALE : 1f);
        Interpolator interpolator =
                isAccepting != mIsAccepting ? ACCELERATE_DECELERATE : EMPHASIZED_DECELERATE;
        int duration = isAccepting != mIsAccepting ? CONSUMPTION_ANIMATION_DURATION
                : HOVER_ANIMATION_DURATION;
        mIsAccepting = isAccepting;
        mIsHovered = isHovered;
        if (startScale == endScale) {
            if (!mIsAccepting) {
                clearDrawingDelegate();
            }
            mIsHoveredOrAnimating = mIsHovered;
            return;
        }


        mScaleAnimator = ValueAnimator.ofFloat(0f, 1.0f);
        mScaleAnimator.addUpdateListener(animation -> {
            float prog = animation.getAnimatedFraction();
            mScale = prog * endScale + (1 - prog) * startScale;
            invalidate();
        });
        mScaleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (mIsHovered) {
                    mIsHoveredOrAnimating = true;
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mIsAccepting) {
                    clearDrawingDelegate();
                }
                mIsHoveredOrAnimating = mIsHovered;
                mScaleAnimator = null;
            }
        });
        mScaleAnimator.setInterpolator(interpolator);
        mScaleAnimator.setDuration(duration);
        mScaleAnimator.start();
    }

    public void animateToAccept(CellLayout cl, int cellX, int cellY) {
        delegateDrawing(cl, cellX, cellY);
        animateScale(/* isAccepting= */ true, mIsHovered);
    }

    public void animateToRest() {
        animateScale(/* isAccepting= */ false, mIsHovered);
    }

    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    protected void setHovered(boolean hovered) {
        animateScale(mIsAccepting, /* isHovered= */ hovered);
    }
}
