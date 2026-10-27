/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.quickstep.util

import android.animation.Animator
import android.animation.AnimatorSet
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.RotationUtils
import android.view.RemoteAnimationTarget
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import com.android.app.animation.Interpolators.ACCELERATE_1_5
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.anim.AnimationSuccessListener
import com.android.launcher3.anim.AnimatorPlaybackController
import com.android.launcher3.states.StateAnimationConfig
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.quickstep.views.RecentsView
import com.android.quickstep.RemoteAnimationTargets

/** Adapts the swipe-home flight to the position already reached by a predictive-back preview. */
class HomeBackAnimation(
    private val launcher: QuickstepLauncher,
    target: RemoteAnimationTarget,
    previewBounds: RectF,
    sourceBounds: Rect,
    previewCornerRadius: Float,
    targetView: View?,
    private val previewScrim: SurfaceControl?,
) {
    private val dp = launcher.deviceProfile
    private val applier = SurfaceTransactionApplier(launcher.dragLayer)
    private val returningToDrawer = launcher.isInState(LauncherState.ALL_APPS)
    private val icon = targetView?.let { HomeIconAnimation(launcher, it) }
    private var removeScrimOnNextFrame = true
    private var scrimReleased = false
    private var finished = false
    private var completion: Runnable? = null
    private var reveal: ScalingWorkspaceRevealAnim? = null
    private val sourceCrop = RectF(sourceBounds)
    private val homeToWindow = ownerToWindowMatrix(target)
    private val startRect = RectF(previewBounds).also {
        val inverse = Matrix()
        check(homeToWindow.invert(inverse))
        inverse.mapRect(it)
    }
    private val targetRect = icon?.targetRect ?: defaultTargetRect()
    private val spring = HomeWindowAnimation.createSpring(
        launcher, dp, startRect, targetRect, icon?.isInHotseat == true, false,
    )
    private val homeAnimation: AnimatorPlaybackController = if (returningToDrawer) {
        // Back returns to its original drawer; a swipe-home always selects NORMAL.
        AnimatorPlaybackController.wrap(AnimatorSet(), 1)
    } else {
        launcher.stateManager.createAnimationToNewWorkspace(
            LauncherState.NORMAL,
            2L * maxOf(dp.deviceProperties.widthPx, dp.deviceProperties.heightPx),
            StateAnimationConfig.SKIP_ALL_ANIMATIONS,
        )
    }
    private val params = TransformParams {
        SurfaceTransaction().also { transaction ->
            // Hand the preview's blur to the home reveal on the first rendered flight frame.
            // This avoids a separate post-commit scrim animation racing the window/icon.
            if (removeScrimOnNextFrame) {
                if (previewScrim?.isValid == true) transaction.transaction.remove(previewScrim)
                removeScrimOnNextFrame = false
            }
        }
    }.setTargetSet(
        RemoteAnimationTargets(
            arrayOf(target), emptyArray(), emptyArray(), RemoteAnimationTarget.MODE_CLOSING,
        )
    ).setSyncTransactionApplier(applier)
    private val renderer = HomeWindowAnimation(
        sourceCrop, homeToWindow, params, previewCornerRadius, sourceCrop.width() / 2f,
        object : HomeWindowAnimation.Callbacks {
            override fun getWindowAlpha(progress: Float): Float = icon?.getWindowAlpha(progress)
                ?: when {
                    progress <= 0f -> 1f
                    progress >= 0.85f -> 0f
                    else -> Utilities.mapToRange(progress, 0f, 0.85f, 1f, 0f, ACCELERATE_1_5)
                }
            override fun onUpdate(rect: RectF, progress: Float, visibleRadius: Float) {
                icon?.update(rect, progress, visibleRadius, 0)
            }
        },
    )

    init {
        icon?.setAnimation(spring)
        spring.addOnUpdateListener(object : RectFSpringAnim.OnUpdateListener {
            override fun onUpdate(rect: RectF, progress: Float) {
                homeAnimation.setPlayFraction(progress)
                renderer.onUpdate(rect, progress)
            }
            override fun onCancel() { icon?.cancel() }
        })
        spring.addAnimatorListener(object : AnimationSuccessListener() {
            override fun onAnimationSuccess(animation: Animator?) {
                homeAnimation.animationPlayer.end()
                applier.addOnSafeToReleaseCallback {
                    if (!finished) {
                        finished = true
                        releaseScrim()
                        val callback = completion
                        completion = null
                        callback?.run()
                    }
                }
            }
        })
    }

    fun start(onComplete: Runnable) {
        completion = onComplete
        // Same order as swipe-home: start the flight, then its independent workspace reveal.
        homeAnimation.dispatchOnStart()
        spring.start(launcher, dp, PointF())
        if (!returningToDrawer) {
            reveal = icon?.createReveal(true) ?: ScalingWorkspaceRevealAnim(
                launcher, null, null, false, true, false, true, true,
            )
            reveal?.start()
        }
    }

    fun cancel() {
        if (finished) return
        finished = true
        completion = null
        spring.cancel()
        icon?.cancel()
        reveal?.cancelAnimations()
        homeAnimation.animationPlayer.cancel()
        applier.addOnSafeToReleaseCallback { releaseScrim() }
    }

    private fun releaseScrim() {
        if (scrimReleased) return
        scrimReleased = true
        if (removeScrimOnNextFrame && previewScrim?.isValid == true) {
            SurfaceControl.Transaction().use { it.remove(previewScrim).apply() }
        }
        previewScrim?.release()
    }

    private fun defaultTargetRect(): RectF {
        val orientation = launcher
            .getOverviewPanel<RecentsView<QuickstepLauncher, LauncherState>>()
            .pagedOrientationHandler
        val properties = dp.deviceProperties
        val halfIcon = dp.workspaceProfile.iconSizePx / 2
        val x = orientation.getPrimaryValue(
            properties.availableWidthPx, properties.availableHeightPx,
        ) / 2f
        val y = orientation.getSecondaryValue(
            properties.availableWidthPx, properties.availableHeightPx,
        ).toFloat() - dp.hotseatProfile.barSizePx
        return RectF(x - halfIcon, y - halfIcon, x + halfIcon, y + halfIcon)
    }

    private fun ownerToWindowMatrix(target: RemoteAnimationTarget): Matrix {
        val properties = dp.deviceProperties
        val targetRotation = target.windowConfiguration.rotation
        val delta = if (targetRotation == Surface.ROTATION_0 ||
            targetRotation == Surface.ROTATION_90 || targetRotation == Surface.ROTATION_180 ||
            targetRotation == Surface.ROTATION_270
        ) RotationUtils.deltaRotation(properties.rotationHint, targetRotation) else 0
        val width = properties.widthPx.toFloat()
        val height = properties.heightPx.toFloat()
        // Preserve fractional preview bounds and use both dimensions on non-square displays.
        return Matrix().apply {
            when (delta) {
                Surface.ROTATION_90 -> setValues(floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f))
                Surface.ROTATION_180 -> setValues(floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f))
                Surface.ROTATION_270 -> setValues(floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f))
            }
        }
    }
}
