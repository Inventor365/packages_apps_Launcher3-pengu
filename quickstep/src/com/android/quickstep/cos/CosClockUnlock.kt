/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep.cos

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.penguin.luminous.Luminous
import com.android.launcher3.CellLayout
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.cos.CosClock
import com.android.launcher3.cos.LockscreenClockWidgetView
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.quickstep.SystemUiProxy
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.shared.system.smartspace.SmartspaceState
import kotlin.math.PI
import kotlin.math.pow

class CosClockUnlock(private val launcher: QuickstepLauncher) :
    ILauncherUnlockAnimationController.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val failSafe = Runnable { finish() }
    private var flight: Flight? = null
    private val springs = ArrayList<SpringAnimation>()
    private var playing = false

    override fun prepareForUnlock(animateClock: Boolean, clockBounds: Rect, fontHeight: Int) {
        val bounds = Rect(clockBounds)
        main.post {
            if (!animateClock && playing && flight != null) return@post
            prepare(if (animateClock) bounds else null, fontHeight)
        }
    }

    override fun setUnlockAmount(amount: Float, forceIfAnimating: Boolean) {
        main.post { if (amount >= 1f && (forceIfAnimating || !playing)) finish() }
    }

    override fun playUnlockAnimation(unlocked: Boolean, duration: Long, startDelay: Long) {
        main.post {
            if (!unlocked) finish() else if (!playing) play()
        }
    }

    override fun setSmartspaceSelectedPage(selectedPage: Int) {}

    override fun setSmartspaceVisibility(visibility: Int) {}

    override fun dispatchSmartspaceStateToSysui() {
        main.post { dispatchClockState() }
    }

    fun dispatchClockState() {
        val widget = landingClock()
        val state = SmartspaceState()
        val rect = widget?.let { settledClock(it, launcher.dragLayer) }?.first
        if (rect != null) {
            val origin = IntArray(2).also { launcher.dragLayer.getLocationOnScreen(it) }
            rect.offset(origin[0].toFloat(), origin[1].toFloat())
            state.boundsOnScreen = Rect().also { rect.roundOut(it) }
            state.visibleOnScreen = true
        }
        SystemUiProxy.INSTANCE.get(launcher).notifySysuiSmartspaceStateUpdated(state)
    }

    private fun content(): List<View> = listOfNotNull(launcher.workspace, launcher.hotseat)

    private fun prepare(bounds: Rect?, fontHeight: Int) {
        stop()
        playing = false
        content().forEach {
            it.scaleX = ZOOM
            it.scaleY = ZOOM
            it.alpha = 0f
        }
        flight?.remove()
        flight = null
        val widget = landingClock()
        if (bounds != null && widget != null && !bounds.isEmpty) {
            flight =
                if (fontHeight > 0 && widget.fluid != null) FluidFlight(widget, bounds, fontHeight)
                else if (fontHeight <= 0 && widget.fluid == null && widget.snapshot != null) {
                    FaceFlight(widget, bounds, widget.snapshot!!, small = fontHeight < 0)
                } else null
        }
        main.removeCallbacks(failSafe)
        main.postDelayed(failSafe, FAIL_SAFE_MS)
    }

    private fun play() {
        stop()
        playing = true
        val flight = flight
        val views = content()
        views.forEachIndexed { i, view ->
            val iconStiffness = stiffness(ICON_RESPONSE)
            Luminous.spring(view, Luminous.SCALE_X, 1f, iconStiffness, 1f - ICON_BOUNCE)
            Luminous.spring(view, Luminous.SCALE_Y, 1f, iconStiffness, 1f - ICON_BOUNCE)
            val end = if (flight == null && i == views.lastIndex) Runnable { finish() } else null
            Luminous.spring(view, Luminous.ALPHA, 1f, stiffness(ALPHA_RESPONSE), 1f, Float.NaN, end)
        }
        if (flight == null) {
            if (views.isEmpty()) finish()
        } else {
            flight.flying = true
            val progress = FloatValueHolder(0f)
            springs +=
                SpringAnimation(progress).apply {
                    spring = force(1f, CLOCK_RESPONSE, 0f)
                    setMinimumVisibleChange(0.002f)
                    addUpdateListener { _, value, _ -> flight.progress = value }
                    addEndListener { _, _, _, _ -> finish() }
                }
        }
        springs.forEach { it.start() }
    }

    private fun finish() {
        main.removeCallbacks(failSafe)
        stop()
        playing = false
        content().forEach {
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
        }
        flight?.land()
        flight = null
    }

    private fun stop() {
        springs.forEach { it.cancel() }
        springs.clear()
        content().forEach { Luminous.cancelAll(it) }
    }

    private fun stiffness(response: Float) = (2 * PI / response).pow(2).toFloat()

    private fun settledClock(widget: LockscreenClockWidgetView, layer: ViewGroup): Pair<RectF, Float>? {
        val (rect, scale) = widget.clockRectIn(layer) ?: return null
        val page = launcher.workspace ?: return rect to scale
        val zoom = page.scaleX
        if (zoom == 1f || zoom == 0f) return rect to scale
        val pivot = floatArrayOf(page.pivotX, page.pivotY)
        Utilities.getDescendantCoordRelativeToAncestor(page, layer, pivot, false)
        fun settle(v: Float, c: Float) = c + (v - c) / zoom
        return RectF(
            settle(rect.left, pivot[0]),
            settle(rect.top, pivot[1]),
            settle(rect.right, pivot[0]),
            settle(rect.bottom, pivot[1]),
        ) to scale / zoom
    }

    private fun landingClock(): LockscreenClockWidgetView? {
        val workspace = launcher.workspace ?: return null
        if (!launcher.isInState(LauncherState.NORMAL)) return null
        val page = workspace.getPageAt(workspace.nextPage) as? CellLayout ?: return null
        val children = page.shortcutsAndWidgets
        for (i in 0 until children.childCount) {
            (children.getChildAt(i) as? LockscreenClockWidgetView)?.let { return it }
        }
        return null
    }

    private abstract inner class Flight(protected val widget: LockscreenClockWidgetView, bounds: Rect) :
        View(launcher) {
        protected val layer: ViewGroup = launcher.dragLayer
        protected val start = RectF(bounds)

        var progress = 0f
            set(value) {
                field = value
                invalidate()
            }

        var flying = false
            set(value) {
                field = value
                invalidate()
            }

        override fun draw(canvas: Canvas) {
            if (flying) super.draw(canvas)
        }

        init {
            val origin = IntArray(2).also { layer.getLocationOnScreen(it) }
            start.offset(-origin[0].toFloat(), -origin[1].toFloat())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layer.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        abstract fun land()

        protected fun settledClock(): Pair<RectF, Float>? = settledClock(widget, layer)

        fun remove() {
            (parent as? ViewGroup)?.removeView(this)
        }
    }

    private inner class FluidFlight(widget: LockscreenClockWidgetView, bounds: Rect, fontHeight: Int) :
        Flight(widget, bounds) {
        private val clock = widget.fluid!!
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val time = clock.currentTime
        private val from: CosClock.Pose

        init {
            val height = fontHeight.coerceIn(1, 1000)
            from =
                CosClock.Pose(
                    start.left,
                    start.top,
                    CosClock.textSizeFor(paint, time, start.width(), height),
                    height.toFloat(),
                )
            clock.digitsShown = false
            clock.dateAlpha = 0f
        }

        private fun end(): CosClock.Pose {
            val rest = clock.pose()
            val (rect, scale) = settledClock() ?: return from
            return CosClock.Pose(rect.left, rect.top, rest.textSize * scale, rest.height)
        }

        override fun onDraw(canvas: Canvas) {
            CosClock.draw(canvas, paint, time, from.lerp(end(), progress), clock.clockTint, 1f)
        }

        override fun land() {
            remove()
            clock.digitsShown = true
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DATE_FADE_MS
                addUpdateListener { clock.dateAlpha = it.animatedValue as Float }
                start()
            }
        }
    }

    private inner class FaceFlight(
        widget: LockscreenClockWidgetView,
        bounds: Rect,
        private val face: Bitmap,
        private val small: Boolean,
    ) : Flight(widget, bounds) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()

        init {
            widget.setClockHidden(true)
        }

        override fun onDraw(canvas: Canvas) {
            val (end, _) = settledClock() ?: return
            val from =
                if (!small) start
                else {
                    val height = start.width() * face.height / face.width
                    RectF(start.left, start.centerY() - height / 2f, start.right, start.centerY() + height / 2f)
                }
            val p = progress
            box.set(
                from.left + (end.left - from.left) * p,
                from.top + (end.top - from.top) * p,
                from.right + (end.right - from.right) * p,
                from.bottom + (end.bottom - from.bottom) * p,
            )
            paint.alpha = if (small) (255 * (p * 2f).coerceAtMost(1f)).toInt() else 255
            canvas.drawBitmap(face, null, box, paint)
        }

        override fun land() {
            remove()
            widget.setClockHidden(false)
        }
    }

    private companion object {
        const val ZOOM = 1.18f
        const val CLOCK_RESPONSE = 0.3f
        const val ICON_RESPONSE = 0.45f
        const val ICON_BOUNCE = 0.12f
        const val ALPHA_RESPONSE = 0.2f
        const val DATE_FADE_MS = 200L
        const val FAIL_SAFE_MS = 2500L

        fun force(to: Float, response: Float, bounce: Float) =
            SpringForce(to).apply {
                val omega = 2 * PI / response
                stiffness = (omega * omega).toFloat()
                dampingRatio = 1f - bounce
            }
    }
}
