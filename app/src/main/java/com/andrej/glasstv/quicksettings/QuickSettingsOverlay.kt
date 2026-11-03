package com.andrej.glasstv.quicksettings

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.HomeActivity
import com.andrej.glasstv.controlcenter.Controls
import com.andrej.glasstv.controlcenter.Step
import com.andrej.glasstv.remote.TvSound
import com.andrej.glasstv.ui.EN
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.OverlayWindow
import com.andrej.glasstv.ui.Sounds
import kotlinx.coroutines.delay

/**
 * The quick settings over any app, for the TCL remote's button right of 0 (in place of TCL's own
 * bar): a window of the accessibility service's own along the bottom of the screen, which the
 * service hands the remote's keys while it is open. Like the control center over other apps it
 * sits on a shade rather than a blur, as nothing under it can be read back (an HDMI picture least
 * of all). It goes away by itself when the remote has rested a while, as tvOS's player controls do.
 */
class QuickSettingsOverlay(private val service: AccessibilityService) {
    private val glass get() = GlassApp.instance
    private val model = QuickSettingsModel(glass.picture, glass.volume, glass.sleepTimer, ::toggles, ::setSoundMode, ::openApp)
    private val closer = AppCloser(service)
    private val sounds = Sounds(service, glass.prefs)
    private val handler = Handler(Looper.getMainLooper())
    private val idle = Runnable { hide() }
    private var shown by mutableStateOf(false)

    // Over the home screen, which frosts itself under the controls; the plain shade is for other apps.
    private var overHome by mutableStateOf(false)

    /** The quick settings are up, and the remote's arrows, OK and Back are theirs. */
    var isOpen = false
        private set

    /** Close is quitting an app: the TV's settings are on screen under a cover, and the remote waits. */
    val isClosing get() = closer.busy

    private val window = OverlayWindow(service, 1f, Gravity.START, QUICK_REACH) {
        // Laid out on the whole screen, its bottom on the window's: the controls' places are the screen's.
        Box(Modifier.fillMaxWidth().fillMaxHeight().wrapContentHeight(Alignment.Bottom, unbounded = true).requiredHeight(540.dp)) {
            val shade = remember { Animatable(0f) }
            LaunchedEffect(shown) {
                if (shown) {
                    shade.animateTo(1f, tween(260, easing = Motion.EaseOut))
                } else {
                    delay(50)
                    shade.animateTo(0f, tween(240))
                }
            }
            Canvas(Modifier.fillMaxSize()) { if (!overHome) drawQuickShade(QUICK_SHADE * shade.value) }
            QuickSettingsPanel(model, shown)
        }
    }

    fun toggle() = if (isOpen) hide() else show()

    fun show() {
        model.reset()
        val home = HomeActivity.front
        overHome = home != null
        home?.quickSettings(true)
        window.show()
        isOpen = true
        shown = true
        rest()
    }

    fun hide() {
        handler.removeCallbacks(idle)
        stopHolding()
        HomeActivity.front?.quickSettings(false)
        isOpen = false
        shown = false
        window.hide(afterMs = 380)
    }

    /** A key while the quick settings are open: true when it is theirs. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        if (down) rest()
        // Another button pressed meanwhile lets go of a bar.
        if (down && event.keyCode != heldKey) stopHolding()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(Step.Up, event)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(Step.Down, event)
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow(Step.Left, event)
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow(Step.Right, event)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (first) select()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (first && !model.back()) hide()
            // Any other button (Home among them) closes them and does what it always does.
            else -> {
                if (down) hide()
                return false
            }
        }
        return true
    }

    /** Starts the wait for the remote to rest again. */
    private fun rest() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    private fun move(step: Step, event: KeyEvent) {
        val adjusting = model.adjusting && (step == Step.Left || step == Step.Right)
        if (model.move(step, event.repeatCount) && !adjusting) sounds.tick()
    }

    /**
     * Left or Right. Held on a bar, it keeps moving the bar till it is let go, in bigger steps the
     * longer: Android repeats no key an accessibility service takes (the remote only says down and
     * up), so the quick settings repeat it themselves.
     */
    private fun arrow(step: Step, event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when {
                event.repeatCount == 0 -> {
                    stopHolding()
                    move(step, event)
                    if (model.adjusting) {
                        heldKey = event.keyCode
                        heldStep = step
                        heldCount = 0
                        handler.postDelayed(repeatHeld, HOLD_DELAY_MS)
                    }
                }
                // Where the system does repeat a key, the bar has its own repeat already.
                heldKey == 0 -> move(step, event)
            }
            KeyEvent.ACTION_UP -> if (event.keyCode == heldKey) stopHolding()
        }
    }

    private var heldKey = 0
    private var heldStep = Step.Right
    private var heldCount = 0
    private val repeatHeld = object : Runnable {
        override fun run() {
            heldCount++
            rest()
            // At either end, or once the bar has lost the focus, there's nothing more to move.
            if (!model.adjusting || !model.move(heldStep, heldCount)) {
                heldKey = 0
                return
            }
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    private fun stopHolding() {
        handler.removeCallbacks(repeatHeld)
        heldKey = 0
    }

    private fun select() {
        val action = model.select()
        if (action == null) {
            sounds.tick()
            return
        }
        sounds.select()
        hide()
        val app = model.openApp
        if (action == QuickAction.CloseApp && app != null) closer.close(app) else run(service, action)
    }

    /**
     * Sets TCL's sound mode as TCL's voice assistant does ([TvSound], by the name the TV shows for it,
     * in English), which also opens TCL's own panel for it: the panel goes again as soon as the TV has
     * taken the mode, about a second later, and the tab then shows what the TV says.
     */
    private fun setSoundMode(mode: Int) {
        handler.removeCallbacks(soundTaken)
        if (glass.picture.demo) demoSoundMode = mode
        if (glass.picture.demo || !TvSound.set(service, EN.soundModes[mode])) {
            model.refreshToggles()
            return
        }
        soundWanted = mode
        soundUntil = SystemClock.uptimeMillis() + SOUND_WAIT_MS
        handler.postDelayed(soundTaken, SOUND_LOOK_MS)
    }

    private var soundWanted = -1
    private var soundUntil = 0L
    private val soundTaken = object : Runnable {
        override fun run() {
            if (soundMode() != soundWanted && SystemClock.uptimeMillis() < soundUntil) {
                handler.postDelayed(this, SOUND_LOOK_MS)
                return
            }
            TvSound.close(service)
            model.refreshToggles()
        }
    }

    /** The sound mode TCL's audio service notes; a made-up one in the debug demo, for the emulator. */
    private fun soundMode(): Int? {
        if (glass.picture.demo) return demoSoundMode
        return runCatching { Settings.System.getInt(service.contentResolver, TCL_SOUND_MODE, -1) }.getOrDefault(-1).takeIf { it >= 0 }
    }

    private var demoSoundMode = 3

    /**
     * The TV's switches as its settings hold them. TCL's own keys may be closed to apps (Android 12
     * keeps hidden settings to the system), so each is only read where it can be.
     */
    private fun toggles(): QuickToggles {
        val resolver = service.contentResolver
        fun global(key: String) = runCatching { Settings.Global.getInt(resolver, key, -1) }.getOrDefault(-1).takeIf { it >= 0 }
        return QuickToggles(
            soundMode = soundMode(),
            cec = global(HDMI_CONTROL_ENABLED)?.let { it == 1 },
            bluetooth = global(Settings.Global.BLUETOOTH_ON)?.let { it == 1 },
        )
    }

    /**
     * The app in front, for Close: none on the home screen, over the TV's own player (an input: HDMI 1
     * is no app to quit) or over the TV's settings, whose own page would be the one quitting it.
     */
    private fun openApp(): OpenApp? {
        val pkg = glass.recents.front ?: return null
        if (pkg == glass.inputs.playerPackage || pkg == closer.settingsPackage) return null
        val label = glass.apps.all.firstOrNull { it.packageName == pkg }?.label
            ?: runCatching { service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            ?: return null
        return OpenApp(pkg, label)
    }

    fun destroy() {
        handler.removeCallbacks(idle)
        stopHolding()
        handler.removeCallbacks(soundTaken)
        window.destroy()
        closer.destroy()
        sounds.release()
    }

    companion object {
        /** How long the quick settings stay up with the remote at rest. */
        private const val IDLE_MS = 15_000L

        /** A bar held: how long before it starts moving by itself, and how often it moves then. */
        private const val HOLD_DELAY_MS = 380L
        private const val REPEAT_MS = 70L

        /** How long TCL gets to take a sound mode, and how often it is asked meanwhile. */
        private const val SOUND_WAIT_MS = 3_000L
        private const val SOUND_LOOK_MS = 100L

        // TCL's own pop-up choices and panels, which its Quick Settings open (TvSettings'
        // ShowWindowService, exported without a permission): the sound mode and where the sound goes
        // are only TCL's to set (its audio service wants MODIFY_AUDIO_ROUTING), and so are HDR
        // pictures' presets.
        private const val TCL_SETTINGS = "com.android.tv.settings"
        private const val TCL_WINDOW = "com.tcl.settings.ShowWindowService"
        private const val SOUND_SETTINGS = "com.android.tv.action.VIEW_SOUND_SETTINGS"

        // The screens TCL's Quick Settings open for the rest (TvSettings' exported activities; its
        // energy screens aren't, so the quick settings have no Energy button).
        private const val GAME_MODE = "com.android.tv.settings.gamemode"
        private const val CEC = "com.android.tv.settings.CEC"
        private const val HDMI_CONTROL_ENABLED = "hdmi_control_enabled"
        // Where TCL's audio service keeps the sound mode (TclSoundEffect), which only it may set.
        private const val TCL_SOUND_MODE = "tcl_sys_audio_sound_mode"

        /** Opens what [action] is for; false when nothing on the TV took it. Also for the iPhone app. */
        fun run(context: Context, action: QuickAction): Boolean =
            when (action) {
                QuickAction.Speakers -> tclWindow(context, "audio_output") || open(context, Intent(SOUND_SETTINGS))
                QuickAction.Sound -> tclWindow(context, "sound_preset") || open(context, Intent(SOUND_SETTINGS))
                QuickAction.PictureMenu, QuickAction.PictureSettings ->
                    tclWindow(context, "picture") || open(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))
                QuickAction.DisplaySound -> tclWindow(context, "device_display_sound") || open(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))
                QuickAction.Bluetooth -> Controls.bluetoothSettings(context)
                QuickAction.Network -> Controls.wifiSettings(context)
                QuickAction.GameMaster -> open(context, Intent(GAME_MODE))
                QuickAction.Cec -> open(context, Intent(CEC))
                QuickAction.Settings -> Controls.systemSettings(context)
                // Only the quick settings over the app quit it ([AppCloser]): it takes the accessibility service.
                QuickAction.CloseApp -> false
            }

        /** One of TCL's pop-ups; Back closes it back to what was in front (TCL opens its Settings otherwise). */
        private fun tclWindow(context: Context, type: String): Boolean = runCatching {
            val intent = Intent("com.tcl.settings.SHOW_WINDOW")
                .setClassName(TCL_SETTINGS, TCL_WINDOW)
                .putExtra("Type", type)
                .putExtra("exitStartGoogleSettings", false)
            context.startService(intent) != null
        }.getOrDefault(false)

        private fun open(context: Context, intent: Intent) =
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
