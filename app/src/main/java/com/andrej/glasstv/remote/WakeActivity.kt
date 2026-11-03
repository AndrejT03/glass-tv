package com.andrej.glasstv.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * The iPhone app's Turn On: a window that turns the screen on as it shows, as an alarm clock's does,
 * and goes again at once, leaving what was in front before the TV slept. Nothing of it is seen: it is
 * transparent, without animation, in a task of its own (so Glass's home screen doesn't come up with
 * it). TCL's power manager ignores a wake lock that asks for a wake-up (seen 2026-11-01); a window
 * that turns the screen on is what the system itself wakes for, as it starts (turnScreenOn and
 * showWhenLocked in the manifest: set in onCreate, they come after the system has paused it for the
 * sleep). Glass may start it from the background while its accessibility service is on.
 */
class WakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        // A moment on screen, for the screen to come on with it.
        handler.postDelayed(::finish, SHOWN_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val SHOWN_MS = 400L

        fun start(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(context, WakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }.isSuccess
    }
}
