package com.andrej.glasstv.controlcenter

import android.accessibilityservice.AccessibilityService
import android.view.Gravity
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.OverlayWindow
import com.andrej.glasstv.ui.Sounds
import com.andrej.glasstv.ui.stringsFor
import kotlinx.coroutines.delay

/**
 * The control center over any app, for the remote's Home button held down (as the TV button is on
 * Apple TV): a window of the accessibility service's own at the right of the screen, which the
 * service hands the remote's keys while it is open. Like the inputs menu it sits on a shade rather
 * than a blur, as nothing under it can be read back (an HDMI picture least of all).
 */
class ControlCenterOverlay(private val service: AccessibilityService) {
    private val glass get() = GlassApp.instance
    private val model = ControlCenterModel(glass.volume, glass.ambient)
    private val sounds = Sounds(service, glass.prefs)
    private var shown by mutableStateOf(false)

    /** The control center is up, and the remote's arrows, OK and Back are its. */
    var isOpen = false
        private set

    private val window = OverlayWindow(service, CONTROL_REACH, Gravity.END) {
        // Laid out on the whole screen, its right edge on the window's: the tiles' places are the
        // screen's, as on the home screen.
        Box(Modifier.fillMaxHeight().wrapContentWidth(Alignment.End, unbounded = true).requiredWidth(960.dp)) {
            val shade = remember { Animatable(0f) }
            LaunchedEffect(shown) {
                if (shown) {
                    shade.animateTo(1f, tween(260, easing = Motion.EaseOut))
                } else {
                    delay(50)
                    shade.animateTo(0f, tween(240))
                }
            }
            Canvas(Modifier.fillMaxSize()) { drawControlShade(CONTROL_SHADE * shade.value) }
            ControlCenterPanel(model, shown, frost = null)
        }
    }

    fun toggle() = if (isOpen) hide() else show()

    fun show() {
        model.reset()
        model.ambient.open()
        window.show()
        isOpen = true
        shown = true
    }

    fun hide() {
        model.ambient.close()
        isOpen = false
        shown = false
        window.hide(afterMs = 380)
    }

    /** A key while the control center is open: true when it is the control center's. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(Step.Up)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(Step.Down)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) move(Step.Left)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) move(Step.Right)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (first) select()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (first && !model.back()) hide()
            // Any other button (Home among them) closes it and does what it always does.
            else -> {
                if (down) hide()
                return false
            }
        }
        return true
    }

    private fun move(step: Step) {
        val adjusting = model.adjusting
        if (model.move(step) && !adjusting) sounds.tick()
    }

    private fun select() {
        val tile = model.select()
        if (tile == null) {
            sounds.tick()
            return
        }
        sounds.select()
        if (Controls.run(service, tile)) {
            hide()
        } else if (tile == Tile.Power) {
            model.notice = stringsFor(glass.prefs.language).needsService
        }
    }

    fun destroy() {
        window.destroy()
        sounds.release()
    }
}
