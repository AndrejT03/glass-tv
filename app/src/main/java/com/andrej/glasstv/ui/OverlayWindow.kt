package com.andrej.glasstv.ui

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.andrej.glasstv.GlassApp
import kotlin.math.ceil

/**
 * A window of Glass's accessibility service over whatever app is in front: it takes no focus and
 * pauses nothing, so an HDMI picture keeps playing under it (the service hands it the remote's
 * keys). It is only [widthFraction] of the screen wide, at the [gravity] side, and [heightFraction]
 * of it high, at the bottom: the system composites it over the picture on the GPU, and an empty
 * part would cost as much as the rest.
 *
 * Hidden once its content has gone (a full-screen window costs the TV's GPU even with nothing in
 * it), but it stays composed and laid out, so it comes up at once, the first time too.
 */
class OverlayWindow(
    service: AccessibilityService,
    widthFraction: Float,
    gravity: Int,
    heightFraction: Float = 1f,
    content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val view: ComposeView
    private val conceal = Runnable { view.visibility = View.INVISIBLE }

    init {
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val recomposer = unscaledRecomposer(service, lifecycle)
        val glass = GlassApp.instance
        view = ComposeView(service).apply {
            visibility = View.INVISIBLE
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setParentCompositionContext(recomposer)
            setContent {
                DesignScale {
                    CompositionLocalProvider(LocalStrings provides stringsFor(glass.prefs.language), content = content)
                }
            }
        }
        val metrics = service.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            ceil(metrics.widthPixels * widthFraction).toInt(),
            if (heightFraction < 1f) ceil(metrics.heightPixels * heightFraction).toInt() else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { this.gravity = gravity or if (heightFraction < 1f) Gravity.BOTTOM else Gravity.TOP }
        windows.addView(view, params)
    }

    fun show() {
        handler.removeCallbacks(conceal)
        view.visibility = View.VISIBLE
    }

    /** Hides the window once its content has had [afterMs] to animate away. */
    fun hide(afterMs: Long) {
        handler.removeCallbacks(conceal)
        handler.postDelayed(conceal, afterMs)
    }

    fun destroy() {
        handler.removeCallbacks(conceal)
        view.disposeComposition()
        windows.removeView(view)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
