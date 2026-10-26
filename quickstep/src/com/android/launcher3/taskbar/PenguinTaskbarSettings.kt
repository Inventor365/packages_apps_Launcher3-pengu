/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.taskbar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherPrefs.Companion.TASKBAR_PINNING

/** The taskbar's settings under Settings > System > Taskbar, shown on tablets. */
object PenguinTaskbarSettings {
    private const val HOVER_DELAY = "penguin_taskbar_hover_delay"
    private const val AUTOHIDE_TIMEOUT = "penguin_taskbar_autohide_timeout"
    private const val AUTOHIDE = "penguin_taskbar_autohide"
    private const val DOCK = "penguin_taskbar_dock"
    private const val DOCK_BOUNCE = "penguin_taskbar_dock_bounce"
    private const val DOCK_GLASS = "penguin_taskbar_dock_glass"
    private const val DOCK_BLUR = "penguin_taskbar_dock_blur"

    private const val DEFAULT_HOVER_DELAY_MS = 150
    private const val MAX_HOVER_DELAY_MS = 1000
    private const val DEFAULT_AUTOHIDE_TIMEOUT_S = 5
    private const val MAX_AUTOHIDE_TIMEOUT_S = 30
    private const val DEFAULT_DOCK_BLUR_PERCENT = 60

    private const val ACTION_TASKBAR_SETTINGS = "android.settings.TASKBAR_SETTINGS"
    private const val SETTINGS_PACKAGE = "com.android.settings"

    /** Opens Settings > System > Taskbar. */
    @JvmStatic
    fun settingsIntent(): Intent =
        Intent(ACTION_TASKBAR_SETTINGS)
            .setPackage(SETTINGS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Settings the taskbar is built from, so it is recreated when one of them changes. */
    @JvmField
    val DOCK_URIS: List<Uri> = listOf(DOCK, DOCK_BOUNCE, DOCK_GLASS, DOCK_BLUR).map(Settings.Secure::getUriFor)

    /** How long the pointer rests at the bottom edge before the hidden taskbar is revealed. */
    @JvmStatic
    fun hoverDelayMs(context: Context): Long =
        get(context, HOVER_DELAY, DEFAULT_HOVER_DELAY_MS).coerceIn(0, MAX_HOVER_DELAY_MS).toLong()

    /** How long the taskbar stays up without being used before it hides again. */
    @JvmStatic
    fun autoHideTimeoutMs(context: Context): Long =
        get(context, AUTOHIDE_TIMEOUT, DEFAULT_AUTOHIDE_TIMEOUT_S)
            .coerceIn(1, MAX_AUTOHIDE_TIMEOUT_S) * 1000L

    /**
     * Whether a desktop shows the dock (macOS-style taskbar): the tablet's floating dock, as wide
     * as its apps, in place of the stock full-width taskbar. The dock always auto-hides, so the
     * taskbar's "Always show" setting does not apply to it. Off, the desktop keeps the stock
     * AOSP taskbar, including "Always show".
     */
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun showsDesktopDock(context: Context, launcherPrefs: LauncherPrefs): Boolean =
        get(context, DOCK, 0) != 0

    /** Settings > Taskbar > Auto-hide: tablet mode's "Always show" switch, inverted. */
    @JvmField val AUTOHIDE_URI: Uri = Settings.Secure.getUriFor(AUTOHIDE)

    /**
     * Applies Settings > Taskbar > Auto-hide to tablet mode's "Always show" switch. Desktop mode
     * keeps its own choice from the taskbar menu, so changing one mode never changes the other.
     * The first time, the setting starts from the launcher's current tablet choice.
     */
    @JvmStatic
    fun syncAutoHideToPrefs(context: Context, launcherPrefs: LauncherPrefs) {
        val resolver = context.contentResolver
        if (Settings.Secure.getString(resolver, AUTOHIDE) == null) {
            Settings.Secure.putInt(resolver, AUTOHIDE, if (launcherPrefs.get(TASKBAR_PINNING)) 0 else 1)
            return
        }
        val pinned = get(context, AUTOHIDE, 1) == 0
        if (launcherPrefs.get(TASKBAR_PINNING) != pinned) {
            launcherPrefs.put(TASKBAR_PINNING, pinned)
        }
    }

    /** Reflects a tablet mode "Always show" change from the taskbar menu in Settings. */
    @JvmStatic
    fun syncPinningToAutoHide(context: Context, pinned: Boolean) {
        if ((get(context, AUTOHIDE, 1) == 0) != pinned) {
            Settings.Secure.putInt(context.contentResolver, AUTOHIDE, if (pinned) 0 else 1)
        }
    }

    /** Whether the dock's icons hop when the pointer moves over them. */
    @JvmStatic fun isDockBounceEnabled(context: Context): Boolean = get(context, DOCK_BOUNCE, 1) != 0

    /** Whether the dock is frosted glass with a lit rim. */
    @JvmStatic fun isDockGlassEnabled(context: Context): Boolean = get(context, DOCK_GLASS, 0) != 0

    /** How strongly the glass dock blurs what is behind it, from 0 (clear glass) to 1. */
    @JvmStatic
    fun dockBlurFraction(context: Context): Float =
        get(context, DOCK_BLUR, DEFAULT_DOCK_BLUR_PERCENT).coerceIn(0, 100) / 100f

    private fun get(context: Context, key: String, default: Int): Int =
        Settings.Secure.getInt(context.contentResolver, key, default)
}
