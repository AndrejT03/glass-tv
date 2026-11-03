package com.andrej.glasstv.remote

import android.content.Context
import android.content.Intent

/**
 * TCL's sound mode, set the way TCL's voice assistant sets it: TvSettings' ShowWindowService
 * (exported without a permission) takes a SET of a quick setting by the name of its choice as the TV
 * shows it, sets it and shows TCL's panel for it (`startSettingDialog`, read out of TvSettings,
 * 2026-10-31); [close] takes the panel away. Nothing else may set it: TCL's audio service wants
 * MODIFY_AUDIO_ROUTING, and TCL's sound pop-up only takes a mode from the remote's arrows.
 */
object TvSound {
    /**
     * The sound modes the TV's own pop-up offers, by their numbers in `tcl_sys_audio_sound_mode`
     * (Strings.soundModes), in its order: Intelligent, Standard, Movie, Music, Voice, Game, Sport.
     */
    val MODES = listOf(7, 0, 1, 2, 3, 4, 5)

    /** Asks TCL to set the mode called [name]; false when TvSettings isn't there to ask. */
    fun set(context: Context, name: String): Boolean = window(
        context,
        Intent(SHOW_WINDOW)
            .putExtra("Type", "Settings")
            .putExtra("ITEM", "sound_preset")
            .putExtra("MOTION", "SET")
            .putExtra("MOTION_VALUE", name),
    )

    /** Closes TCL's settings panel. */
    fun close(context: Context) {
        window(context, Intent(DISMISS))
    }

    private fun window(context: Context, intent: Intent): Boolean =
        runCatching { context.startService(intent.setClassName(TCL_SETTINGS, TCL_WINDOW)) != null }.getOrDefault(false)

    private const val TCL_SETTINGS = "com.android.tv.settings"
    private const val TCL_WINDOW = "com.tcl.settings.ShowWindowService"
    private const val SHOW_WINDOW = "com.tcl.settings.SHOW_WINDOW"
    private const val DISMISS = "com.tcl.quickmenu.dismiss"
}
