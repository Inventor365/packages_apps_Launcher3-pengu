/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.statehandlers

import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.launcher3.Flags
import com.android.launcher3.LauncherState
import com.android.launcher3.QuickstepTransitionManager
import com.android.launcher3.R
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.util.ListenableRef

/** Controls blur and wallpaper zoom, for the Launcher surface only. */
class LauncherDepthController(
    private val launcher: QuickstepLauncher,
    blurState: ListenableRef<Boolean>,
) : DepthController<LauncherState, QuickstepLauncher>(launcher, blurState) {

    /**
     * Blurs the workspace and hotseat along with the wallpaper whatever the state, as the COS
     * experience does for home while a closing app flies into it.
     */
    var blurWorkspaceWithWallpaper = false
        set(value) {
            if (field != value) {
                field = value
                blurWorkspaceDepthTargets()
            }
        }

    override fun setState(toState: LauncherState) {
        if (toState == LauncherState.NORMAL && launcher.isHomeRevealAnimationRunning) return
        super.setState(toState)
    }

    override fun shouldBlur(): Boolean {
        return super.shouldBlur() && !Flags.allAppsSurface()
    }

    override fun onDepthAndBlurApplied() {
        super.onDepthAndBlurApplied()
        blurWorkspaceDepthTargets()
    }

    /** Returns `true` if the workspace should be blurred. This return value is only for tests. */
    @VisibleForTesting
    fun blurWorkspaceDepthTargets(): Boolean {
        if (Flags.allAppsSurface()) {
            // AllApps is in a separate layer, so we use background blur and don't blur views.
            return false
        }
        val stateManager = launcher.stateManager
        val targetState = stateManager.targetState ?: stateManager.state
        // Only blur workspace if the current state wants to blur based on the target state.
        val shouldBlurWorkspace =
            blurWorkspaceWithWallpaper ||
                stateManager.currentStableState.shouldBlurWorkspace(launcher, targetState)

        val cosTablet = QuickstepTransitionManager.cosExperience(launcher) &&
            launcher.deviceProfile.deviceProperties.isLargeScreen
        // Match COS's content blur in dp. Blurring a 150dp offscreen workspace layer extends
        // far beyond its edges; the 51dp reference radius preserves text/widget silhouettes.
        val radius = if (cosTablet) {
            mCurrentBlur * minOf(1f,
                launcher.resources.getDimension(R.dimen.cos_tablet_content_blur_radius) / mMaxBlurRadius)
        } else mCurrentBlur.toFloat()
        // Drop the near-invisible tail of the tablet blur instead of allocating ever larger
        // blur textures in the last frames of a close. The radius curve itself is unchanged.
        val minRadius = if (cosTablet) {
            launcher.resources.getDimension(R.dimen.cos_tablet_content_blur_min_radius)
        } else 0f
        val blurEffect =
            if (shouldBlurWorkspace && radius > minRadius)
                RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    if (cosTablet) Shader.TileMode.CLAMP else Shader.TileMode.DECAL,
                ) // If blur is not desired, clear the blur effect from the depth targets.
            else null
        // The dock search pill sits on the hotseat top edge, so any blur of the hotseat is
        // clipped there. COS keeps its dock sharp and unscaled while home blurs behind it.
        val hotseatBlurEffect = if (cosTablet) null else blurEffect
        Log.d(
            TAG,
            "shouldBlurWorkspace: $shouldBlurWorkspace" +
                " targetState: $targetState" +
                " currentStableState: ${stateManager.currentStableState}" +
                " mCurrentBlur: $mCurrentBlur" +
                " mLauncher.getDepthBlurTargets(): ${launcher.depthBlurTargets}",
        )
        launcher.depthBlurTargets.forEach {
            it.setRenderEffect(if (it === launcher.hotseat) hotseatBlurEffect else blurEffect)
        }
        return shouldBlurWorkspace
    }
}
