/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.taskbar

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import com.android.launcher3.R
import com.android.launcher3.Reorderable
import com.android.launcher3.util.MultiTranslateDelegate.INDEX_TASKBAR_HOVER_BOUNCE

/** A dock icon's hop when the pointer moves onto it, as a Mac's dock icons hop. */
object TaskbarDockBounce {
    private const val RISE_MS = 140L
    private const val FALL_MS = 420L

    /** Hops [icon] once, unless it is still in the air from the last hop. */
    @JvmStatic
    fun bounce(icon: View) {
        if (icon !is Reorderable || icon.getTag(R.id.taskbar_dock_bounce) != null) return
        val translation = icon.translateDelegate.getTranslationY(INDEX_TASKBAR_HOVER_BOUNCE)
        val height = icon.resources.getDimension(R.dimen.taskbar_dock_bounce_height)
        val rise =
            ValueAnimator.ofFloat(0f, -height).apply {
                duration = RISE_MS
                interpolator = DecelerateInterpolator()
            }
        val fall =
            ValueAnimator.ofFloat(-height, 0f).apply {
                duration = FALL_MS
                interpolator = BounceInterpolator()
            }
        val hop = AnimatorSet().apply { playSequentially(rise, fall) }
        val update = ValueAnimator.AnimatorUpdateListener { translation.value = it.animatedValue as Float }
        rise.addUpdateListener(update)
        fall.addUpdateListener(update)
        hop.addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    translation.value = 0f
                    icon.setTag(R.id.taskbar_dock_bounce, null)
                }
            }
        )
        icon.setTag(R.id.taskbar_dock_bounce, hop)
        hop.start()
    }
}
