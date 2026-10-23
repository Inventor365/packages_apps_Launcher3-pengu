/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.cos

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.android.systemui.shared.clocks.FluidDigits
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object CosClock {
    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            "penguin_cos_clock",
            Settings.Secure.getInt(context.contentResolver, "penguin_cos_experience", 0),
        ) == 1

    fun heightEm(height: Float): Float = FluidDigits.heightEm(height)

    fun time(context: Context): String =
        DateFormat.format(
                if (DateFormat.is24HourFormat(context)) "HH:mm" else "hh:mm",
                System.currentTimeMillis(),
            )
            .toString()

    fun tint(context: Context): Int {
        val colors =
            runCatching {
                    WallpaperManager.getInstance(context)
                        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                }
                .getOrNull() ?: return 0xFFD9CCFF.toInt()
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(colors.primaryColor.toArgb(), hsl)
        hsl[1] = hsl[1].coerceAtMost(0.6f)
        hsl[2] = 0.84f
        return ColorUtils.HSLToColor(hsl)
    }

    fun draw(canvas: Canvas, paint: Paint, time: String, pose: Pose, tint: Int, alpha: Float): Float {
        val bottom = pose.top + pose.textSize * heightEm(pose.height)
        paint.shader =
            LinearGradient(0f, pose.top, 0f, bottom, -1, tint, Shader.TileMode.CLAMP)
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
        FluidDigits.draw(canvas, time, pose.left, pose.top, pose.textSize, pose.height, paint)
        return FluidDigits.width(time, pose.textSize)
    }

    fun textSizeFor(paint: Paint, time: String, width: Float, height: Int): Float =
        width / FluidDigits.width(time, 1f)

    data class Pose(val left: Float, val top: Float, val textSize: Float, val height: Float) {
        fun lerp(to: Pose, f: Float) =
            Pose(
                left + (to.left - left) * f,
                top + (to.top - top) * f,
                textSize + (to.textSize - textSize) * f,
                height + (to.height - height) * f,
            )
    }
}

class CosClockView(context: Context) : View(context) {
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val datePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            color = -1
            textSize = sp(17f)
        }
    private var time = CosClock.time(context)
    private var date = ""
    private var tint = CosClock.tint(context)

    var digitsShown = true
        set(value) {
            field = value
            invalidate()
        }

    var dateAlpha = 1f
        set(value) {
            field = value
            invalidate()
        }

    private val ticker =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }

    init {
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        context.registerReceiver(ticker, filter, Context.RECEIVER_EXPORTED)
        tint = CosClock.tint(context)
        refresh()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(ticker)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        time = CosClock.time(context)
        date = SimpleDateFormat("d MMM EEE", Locale.getDefault()).format(Date())
        invalidate()
    }

    val currentTime: String
        get() = time

    val clockTint: Int
        get() = tint

    fun pose(): CosClock.Pose {
        val dateRow = sp(17f) + dp(10f)
        val size = minOf(dp(96f), width * 0.75f / FluidDigits.width(time, 1f))
        val room = height - dateRow
        val h = ((room / size - 0.742f) / 0.003456f).coerceIn(50f, HOME_HEIGHT)
        val left = (width - FluidDigits.width(time, size)) / 2f
        val block = dateRow + size * CosClock.heightEm(h)
        val top = (height - block) / 2f + dateRow
        return CosClock.Pose(left, top, size, h)
    }

    fun digitsRect(): RectF {
        val pose = pose()
        val bottom = pose.top + pose.textSize * CosClock.heightEm(pose.height)
        return RectF(pose.left, pose.top, pose.left + FluidDigits.width(time, pose.textSize), bottom)
    }

    override fun onDraw(canvas: Canvas) {
        val pose = pose()
        datePaint.alpha = (230 * dateAlpha).roundToInt()
        canvas.drawText(date, width / 2f, pose.top - dp(10f), datePaint)
        if (digitsShown) CosClock.draw(canvas, digitPaint, time, pose, tint, 1f)
    }

    private companion object {
        const val HOME_HEIGHT = 330f
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
