package com.andrej.glasstv.controlcenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrej.glasstv.GlassApp

/**
 * Sets the ambient light's own colour from adb, to match the strip's colour by eye: the strip turns
 * it at once, and the colour tile's Default goes back to it from then on. Only the shell and the
 * system may send it (DUMP).
 *
 *     adb shell am broadcast -n com.andrej.glasstv/.controlcenter.AmbientColorReceiver --es rgb 87CEEB
 */
class AmbientColorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rgb = intent.getStringExtra("rgb")?.removePrefix("#")?.toIntOrNull(16) ?: return
        GlassApp.instance.ambient.setDefault(rgb)
    }
}
