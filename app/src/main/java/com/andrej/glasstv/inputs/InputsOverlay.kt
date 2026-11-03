package com.andrej.glasstv.inputs

import android.accessibilityservice.AccessibilityService
import android.view.Gravity
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.OverlayWindow
import com.andrej.glasstv.ui.Sounds
import kotlinx.coroutines.delay

/** How dark the shade is at its deepest, where the list is. */
private const val SHADE = 0.8f

/**
 * The inputs menu over any app, for the remote's Input button: a window of the accessibility
 * service's own, which takes no focus and pauses nothing, so an HDMI picture keeps playing under
 * it. The service hands it the remote's keys while it is open. It sits on a shade out of the
 * bottom-left corner rather than a blur: an HDMI picture is on a plane of the display hardware
 * that the system can't read back, so there is nothing it could blur.
 */
class InputsOverlay(private val service: AccessibilityService) {
    private val glass get() = GlassApp.instance
    private val model = InputsMenuModel({ glass.inputs.list }, { glass.recents.list })
    private val sounds = Sounds(service, glass.prefs)
    private var shown by mutableStateOf(false)

    /** The menu is up, and the remote's arrows, OK and Back are its. */
    var isOpen = false
        private set

    private val window = OverlayWindow(service, SHADE_REACH, Gravity.START) {
        Box(Modifier.fillMaxSize()) {
            val shade = remember { Animatable(0f) }
            LaunchedEffect(shown) {
                if (shown) {
                    shade.animateTo(1f, tween(260, easing = Motion.EaseOut))
                } else {
                    delay(50)
                    shade.animateTo(0f, tween(240))
                }
            }
            // The window is only as wide as the shade; the shade is laid out on the whole screen.
            Canvas(Modifier.fillMaxSize()) { drawCornerShade(SHADE * shade.value, screenW = size.width / SHADE_REACH) }
            InputsList(model, shown)
        }
    }

    fun show() {
        model.reset()
        window.show()
        isOpen = true
        shown = true
    }

    fun hide() {
        isOpen = false
        shown = false
        window.hide(afterMs = 360)
    }

    /** A key while the menu is open: true when it is the menu's. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (down && model.move(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)) sounds.tick()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (first) {
                when (val row = model.select()) {
                    null, InputRow.More -> sounds.tick()
                    is InputRow.Input -> {
                        sounds.select()
                        hide()
                        glass.inputs.watch(service, row.input)
                    }
                    is InputRow.App -> {
                        sounds.select()
                        hide()
                        runCatching { service.startActivity(row.app.launchIntent()) }
                    }
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TV_INPUT -> if (first) hide()
            // Any other button closes the menu and does what it always does.
            else -> {
                if (down) hide()
                return false
            }
        }
        return true
    }

    fun destroy() {
        window.destroy()
        sounds.release()
    }
}
