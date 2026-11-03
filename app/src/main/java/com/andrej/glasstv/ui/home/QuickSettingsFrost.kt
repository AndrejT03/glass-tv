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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.andrej.glasstv.quicksettings.QUICK_SHADE
import com.andrej.glasstv.quicksettings.QuickBackdrop
import com.andrej.glasstv.quicksettings.drawQuickShade
import com.andrej.glasstv.quicksettings.drawQuickShadeOf
import com.andrej.glasstv.ui.Motion
import kotlinx.coroutines.delay

/**
 * The home screen under the quick settings (the TCL remote's button right of 0): the controls are a
 * window of the accessibility service over it, and the bottom of the home screen turns into dark
 * frosted glass under them, a blur of the home screen itself, as under the control center and the
 * inputs menu. The blurred screen, whole, goes to the controls' window for their glass to show.
 */
@Composable
fun BoxScope.QuickSettingsFrost(c: HomeController, frost: Frost) {
    val open = c.quickSettings
    var present by remember { mutableStateOf(false) }

    LaunchedEffect(frost) {
        snapshotFlow { frost.glass }.collect { QuickBackdrop.image = it }
    }

    LaunchedEffect(open) {
        if (open) {
            present = true
            frost.holding = true
            frost.capture()
            frost.amount.animateTo(1f, tween(260, easing = Motion.EaseOut))
        } else if (present) {
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
            if (image != null) drawQuickShadeOf(image, a) else drawQuickShade(QUICK_SHADE * a)
        }
    }
}
