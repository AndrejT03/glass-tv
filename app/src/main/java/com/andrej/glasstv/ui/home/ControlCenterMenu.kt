package com.andrej.glasstv.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.andrej.glasstv.controlcenter.CONTROL_SHADE
import com.andrej.glasstv.controlcenter.ControlCenterPanel
import com.andrej.glasstv.controlcenter.drawControlShade
import com.andrej.glasstv.controlcenter.drawControlShadeOf
import com.andrej.glasstv.ui.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The control center on the home screen (the capsule's button, or the remote's Home button held
 * while the home screen is in front): the top-right corner turns into dark frosted glass and the
 * tiles drop into it, their glass showing the home screen blurred behind them (see
 * [ControlCenterPanel]).
 */
@Composable
fun BoxScope.ControlCenterMenu(c: HomeController, frost: Frost) {
    val open = c.panel == Panel.ControlCenter
    // The tiles in (or on their way out), and the control center there at all.
    var shown by remember { mutableStateOf(false) }
    var present by remember { mutableStateOf(false) }
    // Once opened, the tiles stay composed while hidden: composing them and measuring their text
    // again (the capsules size theirs to fit) took the TV's main thread over 100 ms at every opening.
    var composed by remember { mutableStateOf(false) }

    LaunchedEffect(open) {
        // The light's strip is connected to while the control center is open, so a press acts at once.
        if (open) c.controlCenter.ambient.open() else c.controlCenter.ambient.close()
        if (open) {
            present = true
            composed = true
            frost.holding = true
            frost.capture()
            launch { frost.amount.animateTo(1f, tween(260, easing = Motion.EaseOut)) }
            shown = true
        } else if (present) {
            shown = false
            delay(50)
            frost.amount.animateTo(0f, tween(240))
            frost.clear()
            frost.holding = false
            present = false
        }
    }
    if (present) {
        Canvas(Modifier.fillMaxSize()) {
            val a = frost.amount.value
            if (a <= 0.002f) return@Canvas
            val image = frost.image
            if (image != null) drawControlShadeOf(image, a) else drawControlShade(CONTROL_SHADE * a)
        }
    }
    if (composed) ControlCenterPanel(c.controlCenter, shown, frost.glass)
}
