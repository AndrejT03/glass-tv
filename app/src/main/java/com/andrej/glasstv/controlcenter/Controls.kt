package com.andrej.glasstv.controlcenter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.inputs.InputsKeyService
import com.andrej.glasstv.screensaver.ScreensaverActivity
import kotlin.math.roundToInt

/** What the control center's tiles do, from the home screen or over any app. */
object Controls {
    /** Does what [tile] is for; false when it couldn't (and the control center stays open). */
    fun run(context: Context, tile: Tile): Boolean = when (tile) {
        Tile.Power -> sleep()
        Tile.Wifi -> wifiSettings(context)
        Tile.LastApp -> lastApp(context)
        Tile.Bluetooth -> bluetoothSettings(context)
        Tile.Screensaver -> screensaver(context)
        Tile.Settings -> systemSettings(context)
        // These work in place (ControlCenterModel.select).
        Tile.Performance, Tile.Sound, Tile.Ambient, Tile.AmbientColor -> false
    }

    /**
     * Puts the TV to sleep, as its power button does. Only an accessibility service may (Glass's,
     * the one the Input button needs); false when it is off.
     */
    fun sleep(): Boolean =
        InputsKeyService.running?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true

    /** Back to the app used before the one in front ([RecentApps.previous]). */
    fun lastApp(context: Context): Boolean {
        val app = GlassApp.instance.recents.previous() ?: return false
        return open(context, app.launchIntent())
    }

    fun wifiSettings(context: Context) = open(context, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    /** Android TV has no Bluetooth screen of its own: its devices are under Remotes & Accessories. */
    fun bluetoothSettings(context: Context) = open(
        context,
        Intent(ACCESSORIES),
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun systemSettings(context: Context) = open(context, Intent(Settings.ACTION_SETTINGS))

    fun screensaver(context: Context) = open(context, ScreensaverActivity.intent(context, preview = false))

    /**
     * The Wi-Fi network's name. Android hands it only to apps allowed the TV's location
     * (`adb shell pm grant com.andrej.glasstv android.permission.ACCESS_FINE_LOCATION`); null otherwise.
     */
    fun wifiName(context: Context): String? {
        @Suppress("DEPRECATION")
        val ssid = runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }
            .getOrNull()?.removeSurrounding("\"")
        return ssid?.takeUnless { it.isBlank() || it == "<unknown ssid>" || it == "0x" }
    }

    /** The first of [intents] that something on the TV opens. */
    private fun open(context: Context, vararg intents: Intent): Boolean = intents.any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private const val ACCESSORIES = "com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES"
}

/** The TV's volume (the media stream, which TVs drive their speakers and HDMI-CEC sound bars from). */
@Stable
class Volume(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    /** 0..1. */
    var level by mutableFloatStateOf(0f)
        private set
    var muted by mutableStateOf(false)
        private set

    fun refresh() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        level = now.toFloat() / max
        muted = now == 0 || audio.isStreamMute(AudioManager.STREAM_MUSIC)
    }

    /**
     * [count] steps up or down, without the system's volume bar (the Sound tile and the quick settings'
     * bar show the level).
     */
    fun step(up: Boolean, count: Int = 1) {
        repeat(count) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) }
        refresh()
    }

    /** Sets the level (0..1) at once, as the iPhone app's slider does, also without the system's bar. */
    fun set(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0f, 1f) * max).roundToInt(), 0)
        refresh()
    }

    fun mute(muted: Boolean) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        refresh()
    }

    /** As a percentage. */
    val percent: Int get() = (level * 100f).roundToInt()
}
