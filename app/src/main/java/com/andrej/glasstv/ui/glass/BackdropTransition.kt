package com.andrej.glasstv.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One dissolve shared by the wallpaper and every glass surface, so when the Top Shelf changes the
 * dock, the capsule and the background all turn into the new app together instead of the glass
 * snapping first.
 */
@Stable
class BackdropTransition {
    var current by mutableStateOf<Backdrop?>(null)
        internal set
    var previous by mutableStateOf<Backdrop?>(null)
        internal set
    val progress = Animatable(1f)
}

val LocalBackdropTransition = staticCompositionLocalOf<BackdropTransition?> { null }

@Composable
fun rememberBackdropTransition(target: Backdrop?): BackdropTransition {
    val t = remember { BackdropTransition() }
    LaunchedEffect(target) {
        // A new shelf while the last one is still dissolving in (quick moves along the dock): that one
        // finishes first, quickly, and the next starts from a whole picture. Started over at once, the
        // half-dissolved shelf jumped to full and sharp on the next frame.
        if (t.previous != null) {
            t.progress.animateTo(1f, tween((FINISH_MS * (1f - t.progress.value)).toInt(), easing = LinearEasing))
            t.previous = null
        }
        if (target === t.current) return@LaunchedEffect
        // All three change before the next frame is drawn, so there is never a jump.
        t.previous = t.current
        t.current = target
        if (t.previous == null) return@LaunchedEffect
        t.progress.snapTo(0f)
        t.progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        t.previous = null
    }
    return t
}

/** How long the rest of an interrupted dissolve takes, from its start. */
private const val FINISH_MS = 160f
