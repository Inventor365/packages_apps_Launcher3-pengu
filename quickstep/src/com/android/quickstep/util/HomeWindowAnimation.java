/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.RemoteAnimationTarget;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Utilities;

/** The window renderer and spring selection shared by swipe-home and predictive-back release. */
public final class HomeWindowAnimation implements RectFSpringAnim.OnUpdateListener,
        TransformParams.BuilderProxy {
    public interface Callbacks {
        float getWindowAlpha(float progress);
        default boolean isWidget() { return false; }
        default float getWindowCornerRadius(float progress) { return -1f; }
        void onUpdate(RectF rect, float progress, float visibleRadius);
    }

    private final RectF mSourceCrop;
    private final Rect mCrop = new Rect();
    private final Matrix mHomeToWindow;
    private final Matrix mMatrix = new Matrix();
    private final RectF mWindowRect = new RectF();
    private final RectF mWidgetCrop = new RectF();
    private final Matrix mInverse = new Matrix();
    private final TransformParams mParams;
    private final float mStartRadius;
    private final float mEndRadius;
    private final Callbacks mCallbacks;

    public HomeWindowAnimation(RectF sourceCrop, Matrix homeToWindow, TransformParams params,
            float startRadius, float endRadius, Callbacks callbacks) {
        mSourceCrop = new RectF(sourceCrop);
        sourceCrop.roundOut(mCrop);
        mHomeToWindow = new Matrix(homeToWindow);
        mParams = params;
        mStartRadius = startRadius;
        mEndRadius = endRadius;
        mCallbacks = callbacks;
    }

    public static RectFSpringAnim createSpring(Context context, DeviceProfile dp, RectF start,
            RectF target, boolean inHotseat, boolean widget) {
        boolean taskbarHotseat = dp.getDeviceProperties().getTaskbarConfiguration()
                .isTaskbarPresent() && inHotseat;
        return new RectFSpringAnim(taskbarHotseat
                ? new RectFSpringAnim.TaskbarHotseatSpringConfig(context, dp, start, target)
                : (widget ? new RectFSpringAnim.WidgetSpringConfig(context, dp, start, target)
                        : new RectFSpringAnim.DefaultSpringConfig(context, dp, start, target)));
    }

    @Override
    public void onUpdate(RectF currentRect, float progress) {
        float radius = Utilities.mapRange(progress, mStartRadius, mEndRadius);
        mHomeToWindow.mapRect(mWindowRect, currentRect);
        if (mCallbacks.isWidget()) {
            float scale;
            if (mSourceCrop.height() > mSourceCrop.width()) {
                scale = Math.min(mWindowRect.width() / mSourceCrop.width(), 1f);
                float croppedHeight = mSourceCrop.height() - mWindowRect.height() / scale;
                float translate = croppedHeight / 2;
                mWidgetCrop.set(0f, translate, mSourceCrop.width(),
                        mSourceCrop.height() - translate);
                mMatrix.setScale(scale, scale);
                mMatrix.postTranslate(mWindowRect.left,
                        mWindowRect.top - croppedHeight * scale / 2);
            } else {
                scale = Math.min(mWindowRect.height() / mSourceCrop.height(), 1f);
                float croppedWidth = mSourceCrop.width() - mWindowRect.width() / scale;
                float translate = croppedWidth / 2;
                mWidgetCrop.set(translate, 0f, mSourceCrop.width() - translate,
                        mSourceCrop.height());
                mMatrix.setScale(scale, scale);
                mMatrix.postTranslate(mWindowRect.left - croppedWidth * scale / 2,
                        mWindowRect.top);
            }
            mWidgetCrop.round(mCrop);
            float windowRadius = mCallbacks.getWindowCornerRadius(progress);
            if (windowRadius >= 0f) {
                mMatrix.invert(mInverse);
                radius = mInverse.mapRadius(windowRadius);
            }
        } else {
            mMatrix.setRectToRect(mSourceCrop, mWindowRect, Matrix.ScaleToFit.FILL);
        }
        mParams.setTargetAlpha(mCallbacks.getWindowAlpha(progress)).setCornerRadius(radius);
        mParams.applySurfaceParams(mParams.createSurfaceParams(this));
        mCallbacks.onUpdate(currentRect, progress, mMatrix.mapRadius(radius));
    }

    @Override
    public void onBuildTargetParams(SurfaceTransaction.SurfaceProperties builder,
            RemoteAnimationTarget app, TransformParams params) {
        builder.setMatrix(mMatrix).setWindowCrop(mCrop).setCornerRadius(params.getCornerRadius());
    }
}
