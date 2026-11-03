package com.andrej.glasstv.screensaver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.andrej.glasstv.BuildConfig
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.ui.DesignScale
import com.andrej.glasstv.ui.unscaledRecomposer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen saver started by the launcher (idle timer, Back on the top row, or the sparkles button),
 * or as the picker in Settings, where Left/Right step through the savers and OK chooses the one on
 * screen: the caption confirms it and the picker returns to Settings, which now shows the choice.
 */
class ScreensaverActivity : ComponentActivity() {
    private val prefs get() = GlassApp.instance.prefs
    private val preview by lazy { intent.getBooleanExtra(EXTRA_PREVIEW, false) }
    private lateinit var state: SaverState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state = SaverState(this, intent.getStringExtra(EXTRA_SAVER)?.takeIf { BuildConfig.DEBUG && it in SAVERS } ?: prefs.saver)
        if (BuildConfig.DEBUG) state.fast = intent.getBooleanExtra(EXTRA_FAST, false)
        setContent(parent = unscaledRecomposer(this, lifecycle)) {
            DesignScale { ScreensaverHost(state, preview) }
        }
    }

    // Left/Right move to the next or previous shot or photo, as swiping does on Apple TV, and never
    // close the saver; any other button wakes it. Key-ups are ignored: the key that started the
    // saver is still being released when it appears.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            // Already chosen and on its way back to Settings: any button just gets there sooner.
            state.chosen -> finish()
            preview && step != 0 -> state.mode = SAVERS[(SAVERS.indexOf(state.mode) + step + SAVERS.size) % SAVERS.size]
            preview && ok -> {
                prefs.saver = state.mode
                state.chosen = true
                lifecycleScope.launch {
                    delay(CONFIRM_MS)
                    finish()
                }
            }
            step != 0 -> state.skips.tryEmit(step)
            else -> finish()
        }
        return true
    }

    // The iPhone app's next, previous and stop reach a running saver (not the picker).
    override fun onResume() {
        super.onResume()
        if (!preview) ShowingSaver.shown(state) { finish() }
    }

    // Anything coming up over the screen saver (an app, the TV's own panels) ends it: its aerial
    // gave the decoder up the moment it was paused, so there is nothing to come back to.
    override fun onPause() {
        super.onPause()
        ShowingSaver.gone(state)
        if (!isFinishing) finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        private const val EXTRA_PREVIEW = "preview"

        /** How long the picker shows "Chosen" before returning to Settings. */
        private const val CONFIRM_MS = 1_100L

        // Debug builds only, for trying the savers from adb (README, "Testing the screen savers"):
        // which saver to show, and photos and clock layouts changing every few seconds.
        private const val EXTRA_SAVER = "saver"
        private const val EXTRA_FAST = "fast"

        fun intent(context: Context, preview: Boolean) =
            Intent(context, ScreensaverActivity::class.java).putExtra(EXTRA_PREVIEW, preview)
    }
}
