package com.andrej.glasstv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** The screen saver's Start After choices, in minutes, in the settings' order (0 = never). */
val IDLE_MINUTES = listOf(1, 2, 5, 10, 15, 30, 0)

/** Launcher settings, backed by SharedPreferences and observable from Compose. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var homeStyle by string("home_style", "tvos")         // tvos (app Top Shelf) | clock (wallpaper + big clock)
    var wallpaper by string("wallpaper", "aerial")
    var clockStyle by string("clock_style", "glass")      // glass | solid
    var language by string("language", "mk")              // mk | en
    var saver by string("saver", "aerial")                // aerial | landscapes | portraits | dynamic | liquid | standby
    var dynamicWallpaper by string("dynamic_wallpaper", "all") // a dynamic desktop picture's id, or all (in turn)
    var idleMinutes by int("idle_minutes", 5)             // 0 = never
    var aerialQuality by string("aerial_quality", "hd")   // hd (1080p HEVC) | uhd (4K HEVC)
    // big (tvOS 26 numerals) | small (time and date in the corner) | off; was a plain on/off switch
    var saverClock by string("saver_clock_style", if (sp.getBoolean("saver_clock", true)) "big" else "off")
    var sounds by bool("sounds", true)

    /** The household's name for the TV, set from the iPhone app; blank for the system's own. */
    var tvName by string("tv_name", "")
    var order by stringList("order")

    /** Packages of the TV apps opened most recently, most recent first (RecentApps). */
    var recentApps by stringList("recent_apps")

    /**
     * What the household calls the TV's inputs, by input id, over the TV's own labels: the cable TV
     * box is on HDMI 1 (port HW15 of the TCL's TV input service).
     */
    var inputNames by stringMap("input_names", mapOf(CABLE_BOX to "Кабелска телевизија"))

    /** The ambient light behind the TV: the Bluetooth LED strip Ambient Sync drives (its address). */
    var ambientStrip by string("ambient_strip", AMBIENT_STRIP)

    /** The light's colour Glass last set, an index in AmbientLight.COLORS (0: the strip's own, below). */
    var ambientColor by int("ambient_color", 0)

    /** The strip's own colour, as it was before Glass set any (0xRRGGBB): what "Default" goes back to. */
    var ambientDefault by int("ambient_default", AMBIENT_DEFAULT)

    /** Glass turned the strip off and hasn't turned it on since: it owes the strip its colour. */
    var ambientOff by bool("ambient_off", false)

    /** A colour of the household's own for the light (0xRRGGBB), set from the iPhone app; -1 for none. */
    var ambientCustom by int("ambient_custom", -1)

    /**
     * The TV's backlight (0..100) as the household last set it from Glass (the quick settings, the
     * iPhone app), which Glass keeps whatever the picture preset; -1 till then (TvPicture).
     */
    var backlight by int("backlight", -1)

    private companion object {
        /** HDMI 1 on the TCL (its TV input service's port HW15), where the cable TV box is plugged in. */
        const val CABLE_BOX = "com.tcl.tvinput/.TvPassThroughService/HW15"

        /** The household's strip, "ELK-BLEDDM 44" (duoCo), as Ambient Sync lists it. */
        const val AMBIENT_STRIP = "BE:68:57:1D:A6:04"

        /**
         * The household's sky blue (2026-10-28), as the strip had it before Glass first turned it off and
         * on; calibrated with AmbientColorReceiver.
         */
        const val AMBIENT_DEFAULT = 0x87CEEB
    }

    private fun string(key: String, def: String) =
        Pref(def, { sp.getString(key, def) ?: def }) { putString(key, it) }

    private fun int(key: String, def: Int) =
        Pref(def, { sp.getInt(key, def) }) { putInt(key, it) }

    private fun bool(key: String, def: Boolean) =
        Pref(def, { sp.getBoolean(key, def) }) { putBoolean(key, it) }

    private fun stringList(key: String) =
        Pref(emptyList(), { sp.getString(key, "").orEmpty().split('\n').filter { it.isNotEmpty() } }) {
            putString(key, it.joinToString("\n"))
        }

    private fun stringMap(key: String, def: Map<String, String>) =
        Pref(def, {
            sp.getString(key, null)?.split('\n')?.mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: def
        }) { putString(key, it.entries.joinToString("\n") { (k, v) -> "$k\t$v" }) }

    private inner class Pref<T>(
        @Suppress("unused") default: T,
        read: () -> T,
        private val write: SharedPreferences.Editor.(T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(read())

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            sp.edit().apply { write(value) }.apply()
        }
    }
}
