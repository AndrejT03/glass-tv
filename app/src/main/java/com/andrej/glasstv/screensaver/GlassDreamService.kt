package com.andrej.glasstv.screensaver

import android.service.dreams.DreamService
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
import com.andrej.glasstv.ui.DesignScale
import com.andrej.glasstv.ui.unscaledRecomposer

/**
 * The same screen savers as a system screen saver, so they also appear on top of other apps.
 * Google TV hides the picker; select it with
 * `adb shell settings put secure screensaver_components com.andrej.glasstv/.screensaver.GlassDreamService`.
 */
class GlassDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val saver = SaverState(this, GlassApp.instance.prefs.saver).also { state = it }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@GlassDreamService)
            setViewTreeSavedStateRegistryOwner(this@GlassDreamService)
            setParentCompositionContext(unscaledRecomposer(this@GlassDreamService, lifecycle))
            setContent { DesignScale { ScreensaverHost(saver) } }
        }
        setContentView(view)
    }

    // Held for the iPhone app's next, previous and stop, as Glass's own saver is.
    private var state: SaverState? = null

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        state?.let { ShowingSaver.shown(it) { finish() } }
    }

    override fun onDreamingStopped() {
        state?.let(ShowingSaver::gone)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
