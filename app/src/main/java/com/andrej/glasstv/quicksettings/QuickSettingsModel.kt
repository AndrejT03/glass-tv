package com.andrej.glasstv.quicksettings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrej.glasstv.controlcenter.Step
import com.andrej.glasstv.controlcenter.Volume
import com.andrej.glasstv.remote.TvSound

/**
 * The round buttons at the right, where tvOS 26's player has subtitles, audio and picture in picture:
 * the screens TCL's Quick Settings open, in its order.
 */
enum class QuickButton { Speakers, Network, PictureSettings, DisplaySound, Settings }

/**
 * The tabs along the bottom, where tvOS 26's player has Info and Continue Watching, in TCL's order:
 * the settings with a value to show (the switches On or Off), across the whole width. Close, where
 * TCL has Game Master, quits the app in front ([AppCloser]).
 */
enum class QuickPill { Picture, Sound, SleepTimer, CloseApp, Bluetooth, Cec }

/**
 * What the TV says of its settings when the quick settings open: the sound mode (TCL's number for
 * it), HDMI-CEC (TCL's T-Link) and Bluetooth on or off. Null where it doesn't say.
 */
data class QuickToggles(val soundMode: Int?, val cec: Boolean?, val bluetooth: Boolean?)

/** The app in front, which Close quits: its package and its name. */
data class OpenApp(val packageName: String, val label: String)

/** The rows of the quick settings, bottom to top: the tabs, the volume bar, the brightness bar, the round buttons. */
enum class QuickRow { Pills, Volume, Brightness, Buttons }

/** What OK asks the host to do, closing the quick settings for it. */
enum class QuickAction { Speakers, Sound, PictureMenu, Bluetooth, Network, PictureSettings, DisplaySound, Settings, GameMaster, Cec, CloseApp }

/**
 * Where the remote is in the quick settings, and what they show: the picture preset and the
 * backlight as the TV reports them, the volume, the sound mode, the sleep timer. The tabs open their
 * choices in a list above them; a bar changes with Left and Right while it is in focus. [setSound]
 * asks the TV for a sound mode (the host's to do: only TCL's settings app may set it), and
 * [refreshToggles] shows what the TV says then.
 */
@Stable
class QuickSettingsModel(
    private val picture: TvPicture,
    val volume: Volume,
    val timer: SleepTimer,
    private val readToggles: () -> QuickToggles,
    private val setSound: (Int) -> Unit,
    private val readOpenApp: () -> OpenApp?,
) {
    var row by mutableStateOf(QuickRow.Pills)
        private set
    var button by mutableStateOf(QuickButton.Speakers)
        private set
    var pill by mutableStateOf(QuickPill.Picture)
        private set

    /** The tab whose choices are open above it, or null. */
    var list by mutableStateOf<QuickPill?>(null)
        private set

    /** The choice in focus in the open list, top to bottom. */
    var option by mutableIntStateOf(0)
        private set

    /** The picture preset (a TCL mode number), null until it is read or where it can't be. */
    var mode by mutableStateOf<Int?>(null)
        private set

    /** The backlight, 0..100; null until it is read, or where it can't be. */
    var brightness by mutableStateOf<Int?>(null)
        private set

    /** The TV lets Glass set its backlight (TCL's picture service is there): the brightness bar is shown. */
    var hasBrightness by mutableStateOf(false)
        private set

    /** The switches, as [QuickToggles] reads them. */
    var toggles by mutableStateOf(QuickToggles(null, null, null))
        private set

    /** The app Close would quit; null on the home screen and over the TV's inputs (an HDMI picture). */
    var openApp by mutableStateOf<OpenApp?>(null)
        private set

    fun reset() {
        row = QuickRow.Pills
        button = QuickButton.Speakers
        pill = QuickPill.Picture
        list = null
        option = 0
        hasBrightness = picture.available
        toggles = readToggles()
        openApp = readOpenApp()
        volume.refresh()
        picture.read { m, b ->
            mode = m
            brightness = b?.coerceIn(0, 100)
        }
    }

    /** Reads the switches and the sound mode again, as the TV has them now. */
    fun refreshToggles() {
        toggles = readToggles()
    }

    /** How many choices [p]'s list has. */
    fun choices(p: QuickPill) = when (p) {
        QuickPill.Picture -> PictureMode.entries.size
        QuickPill.Sound -> TvSound.MODES.size
        QuickPill.SleepTimer -> SleepTimer.CHOICES.size
        else -> 0
    }

    /** The choice of [p] that is on (-1 for none of them: an HDR preset, a sound mode the list hasn't). */
    fun chosen(p: QuickPill) = when (p) {
        QuickPill.Picture -> PictureMode.entries.indexOfFirst { it.id == mode }
        QuickPill.Sound -> TvSound.MODES.indexOf(toggles.soundMode)
        QuickPill.SleepTimer -> SleepTimer.CHOICES.indexOf(timer.minutes)
        else -> -1
    }

    /**
     * An arrow: the next button, tab or row that way, a choice in the open list, or the volume or the
     * brightness while its bar is in focus ([repeat]: how long the button has been held, for bigger
     * steps). False when nothing changed.
     */
    fun move(step: Step, repeat: Int = 0): Boolean {
        list?.let { open ->
            return when (step) {
                Step.Up -> (option > 0).also { if (it) option-- }
                Step.Down -> (option < choices(open) - 1).also { if (it) option++ }
                else -> false
            }
        }
        return when (row) {
            QuickRow.Pills -> when (step) {
                Step.Left -> (pill.ordinal > 0).also { if (it) pill = QuickPill.entries[pill.ordinal - 1] }
                Step.Right -> (pill.ordinal < QuickPill.entries.lastIndex).also { if (it) pill = QuickPill.entries[pill.ordinal + 1] }
                Step.Up -> true.also { row = QuickRow.Volume }
                Step.Down -> false
            }
            QuickRow.Volume -> when (step) {
                Step.Left, Step.Right -> adjustVolume(step == Step.Right, repeat)
                Step.Up -> true.also { row = if (hasBrightness) QuickRow.Brightness else QuickRow.Buttons }
                Step.Down -> true.also { row = QuickRow.Pills }
            }
            QuickRow.Brightness -> when (step) {
                Step.Left, Step.Right -> adjustBrightness(if (step == Step.Right) 1 else -1, repeat)
                Step.Up -> true.also { row = QuickRow.Buttons }
                Step.Down -> true.also { row = QuickRow.Volume }
            }
            QuickRow.Buttons -> when (step) {
                Step.Left -> (button.ordinal > 0).also { if (it) button = QuickButton.entries[button.ordinal - 1] }
                Step.Right -> (button.ordinal < QuickButton.entries.lastIndex).also { if (it) button = QuickButton.entries[button.ordinal + 1] }
                Step.Up -> false
                Step.Down -> true.also { row = if (hasBrightness) QuickRow.Brightness else QuickRow.Volume }
            }
        }
    }

    /** A bar in focus and moving: the volume or the brightness changes rather than the focus. */
    val adjusting get() = list == null && (row == QuickRow.Brightness || row == QuickRow.Volume)

    /** One step of brightness, bigger the longer the button is held. */
    private fun adjustBrightness(direction: Int, repeat: Int): Boolean {
        val now = brightness ?: return false
        val next = (now + direction * stepFor(repeat)).coerceIn(0, 100)
        if (next == now) return false
        brightness = next
        picture.setBacklight(next)
        return true
    }

    /** One step of volume (of the TV's 100 on the TCL), bigger the longer the button is held. */
    private fun adjustVolume(up: Boolean, repeat: Int): Boolean {
        val before = volume.level
        volume.step(up, stepFor(repeat))
        return volume.level != before
    }

    /** Held, a bar moves one at a time at first, then two, then three. */
    private fun stepFor(repeat: Int) = when {
        repeat < 10 -> 1
        repeat < 25 -> 2
        else -> 3
    }

    /**
     * OK: a choice in the open list is taken (and the list closes), a tab opens its list; or the
     * thing the host does (opening TCL's own sound or speaker choices, Bluetooth, settings).
     */
    fun select(): QuickAction? {
        list?.let { open ->
            when (open) {
                QuickPill.Picture -> {
                    val chosen = PictureMode.entries[option]
                    mode = chosen.id
                    picture.setMode(chosen.id) { now -> if (now != null) mode = now }
                }
                QuickPill.Sound -> {
                    val chosen = TvSound.MODES[option]
                    if (chosen != toggles.soundMode) {
                        toggles = toggles.copy(soundMode = chosen)
                        setSound(chosen)
                    }
                }
                QuickPill.SleepTimer -> timer.set(SleepTimer.CHOICES[option])
                else -> Unit
            }
            list = null
            return null
        }
        return when (row) {
            QuickRow.Buttons -> when (button) {
                QuickButton.Speakers -> QuickAction.Speakers
                QuickButton.Network -> QuickAction.Network
                QuickButton.PictureSettings -> QuickAction.PictureSettings
                QuickButton.DisplaySound -> QuickAction.DisplaySound
                QuickButton.Settings -> QuickAction.Settings
            }
            QuickRow.Volume, QuickRow.Brightness -> null
            QuickRow.Pills -> when (pill) {
                // Presets for HDR pictures, and TVs without TCL's service, are TCL's own menu's to show.
                QuickPill.Picture -> if (picture.available && chosen(QuickPill.Picture) >= 0) open(QuickPill.Picture) else QuickAction.PictureMenu
                // A mode the list hasn't, or none the TV tells of: TCL's own pop-up shows its modes.
                QuickPill.Sound -> if (chosen(QuickPill.Sound) >= 0) open(QuickPill.Sound) else QuickAction.Sound
                QuickPill.SleepTimer -> open(QuickPill.SleepTimer)
                // Nothing to quit on the home screen or over an input: OK leaves the quick settings up.
                QuickPill.CloseApp -> if (openApp != null) QuickAction.CloseApp else null
                // TCL keeps HDMI-CEC in its own service, closed to apps: its own screen sets it.
                QuickPill.Bluetooth -> QuickAction.Bluetooth
                QuickPill.Cec -> QuickAction.Cec
            }
        }
    }

    private fun open(p: QuickPill): QuickAction? {
        list = p
        option = chosen(p).coerceAtLeast(0)
        return null
    }

    /** Back: closes the open list; false when the quick settings should close. */
    fun back(): Boolean {
        if (list == null) return false
        list = null
        return true
    }
}
