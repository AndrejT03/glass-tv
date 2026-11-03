package com.andrej.glasstv.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** tvOS motion vocabulary. */
object Motion {
    val Focus = spring<Float>(dampingRatio = 0.72f, stiffness = 420f)
    val Tilt = spring<androidx.compose.ui.geometry.Offset>(dampingRatio = 0.5f, stiffness = 260f)
    val Scroll = spring<Float>(dampingRatio = 1f, stiffness = 210f)
    val Reorder = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    val Menu = spring<Float>(dampingRatio = 0.74f, stiffness = 460f)
    val Panel = spring<Float>(dampingRatio = 0.86f, stiffness = 300f)
    val Highlight = spring<Float>(dampingRatio = 0.8f, stiffness = 700f)
    val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

/**
 * TV makers often ship with animator scale 0.5, which would double the speed of every spring.
 * This recomposer runs Compose animations at the designed speed (still honouring "animations off"),
 * and pauses its frame clock while the screen is stopped so nothing animates in the background.
 */
fun unscaledRecomposer(context: Context, lifecycle: Lifecycle): Recomposer {
    val systemScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val scale = object : MotionDurationScale {
        override val scaleFactor = if (systemScale == 0f) 0f else 1f
    }
    val ui = AndroidUiDispatcher.CurrentThread
    val clock = PausableMonotonicFrameClock(ui[MonotonicFrameClock]!!)
    val coroutineContext = ui + clock + scale
    val recomposer = Recomposer(coroutineContext)
    val scope = CoroutineScope(coroutineContext)
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> clock.resume()
            Lifecycle.Event.ON_STOP -> clock.pause()
            Lifecycle.Event.ON_DESTROY -> {
                recomposer.cancel()
                scope.cancel()
            }
            else -> Unit
        }
    })
    scope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
    return recomposer
}
