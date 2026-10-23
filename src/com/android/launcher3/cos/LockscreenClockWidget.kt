/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.cos

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.custom.CustomWidget
import kotlin.math.min

object LockscreenClockWidget : CustomWidget {
    override val id: String = "lockscreen-clock-widget"

    override fun updateWidgetInfo(context: Context, info: LauncherAppWidgetProviderInfo) {
        info.label = context.getString(R.string.lockscreen_clock_widget)
        info.previewLayout = 0
        info.previewImage = 0
        info.descriptionRes = 0
        info.resizeMode =
            AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL
        info.spanX = 4
        info.spanY = 2
        info.minSpanX = 2
        info.minSpanY = 1
        info.maxSpanX = Int.MAX_VALUE
        info.maxSpanY = Int.MAX_VALUE
    }

    override fun createView(context: Context, info: LauncherAppWidgetProviderInfo): AppWidgetHostView =
        LockscreenClockWidgetView(context).apply {
            setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, info)
        }
}

class LockscreenClockWidgetView(context: Context) : LauncherAppWidgetHostView(context) {
    private val face = FaceView(context)
    private var session: Messenger? = null
    private var opening = false
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { onState(it); true })

    var fluid: CosClockView? = null
        private set

    val snapshot: Bitmap?
        get() = face.image

    private val release = Runnable {
        send(MSG_DESTROY, 0, 0)
        session = null
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(face, MATCH, MATCH)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MAIN_EXECUTOR.handler.removeCallbacks(release)
        clipToOutline = false
        if (face.parent !== this) {
            removeAllViews()
            addView(face, MATCH, MATCH)
            fluid?.let { addView(it, MATCH, MATCH) }
        }
        open()
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {}

    override fun getErrorView(): View = View(context)

    override fun onDetachedFromWindow() {
        MAIN_EXECUTOR.handler.postDelayed(release, RELEASE_DELAY_MS)
        super.onDetachedFromWindow()
    }

    private fun open() {
        if (session != null || opening) return
        opening = true
        val metrics = resources.displayMetrics
        val request =
            Bundle().apply {
                putInt(KEY_WIDTH, metrics.widthPixels)
                putInt(KEY_HEIGHT, metrics.widthPixels / 2)
                putInt(KEY_DISPLAY_ID, display?.displayId ?: 0)
                putParcelable(KEY_CALLBACK, inbox)
            }
        UI_HELPER_EXECUTOR.execute {
            val reply =
                runCatching { context.contentResolver.call(PROVIDER, METHOD, null, request) }
                    .onFailure { Log.e(TAG, "SystemUI would not run the clock", it) }
                    .getOrNull()
            MAIN_EXECUTOR.execute {
                opening = false
                session = reply?.getParcelable(KEY_CALLBACK, Messenger::class.java)
            }
        }
    }

    private fun send(what: Int, arg1: Int, arg2: Int) {
        runCatching { session?.send(Message.obtain(null, what, arg1, arg2)) }
    }

    private fun onState(message: Message) {
        if (message.what != MSG_STATE) return
        val state = message.data
        state.getParcelable(KEY_SNAPSHOT, Bitmap::class.java)?.let { face.image = it }
        val isFluid = state.getBoolean(KEY_FLUID)
        if (isFluid && fluid == null) {
            fluid = CosClockView(context).also { addView(it, MATCH, MATCH) }
        } else if (!isFluid && fluid != null) {
            removeView(fluid)
            fluid = null
        }
        face.visibility = if (isFluid) INVISIBLE else VISIBLE
    }

    fun setClockHidden(hidden: Boolean) {
        face.alpha = if (hidden) 0f else 1f
    }

    fun clockRectIn(ancestor: View): Pair<RectF, Float>? {
        val target: View = fluid ?: face
        val local = fluid?.digitsRect() ?: face.imageRect() ?: return null
        val point = floatArrayOf(0f, 0f)
        val scale = Utilities.getDescendantCoordRelativeToAncestor(target, ancestor, point, false)
        return RectF(
            point[0] + local.left * scale,
            point[1] + local.top * scale,
            point[0] + local.right * scale,
            point[1] + local.bottom * scale,
        ) to scale
    }

    private class FaceView(context: Context) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        var image: Bitmap? = null
            set(value) {
                field = value
                invalidate()
            }

        fun imageRect(): RectF? {
            val image = image ?: return null
            if (width <= 0 || height <= 0) return null
            val scale = min(width / image.width.toFloat(), height / image.height.toFloat())
            val w = image.width * scale
            val h = image.height * scale
            return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        }

        override fun onDraw(canvas: Canvas) {
            val image = image ?: return
            canvas.drawBitmap(image, null, imageRect() ?: return, paint)
        }
    }

    private companion object {
        const val TAG = "LockscreenClockWidget"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val RELEASE_DELAY_MS = 5000L
        val PROVIDER: Uri = Uri.parse("content://com.android.systemui.customization")

        const val METHOD = "home_clock"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_DISPLAY_ID = "display_id"
        const val KEY_CALLBACK = "callback"
        const val KEY_FLUID = "fluid"
        const val KEY_SNAPSHOT = "snapshot"
        const val MSG_STATE = 1
        const val MSG_DESTROY = 3
    }
}
