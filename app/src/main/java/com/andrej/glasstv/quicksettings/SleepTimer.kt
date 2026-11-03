package com.andrej.glasstv.quicksettings

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrej.glasstv.controlcenter.Controls

/**
 * The sleep timer of the quick settings, as in tvOS 26's Control Center: the TV goes to sleep when
 * it runs out, through Glass's accessibility service (as Power Off does). It counts while the TV is
 * on and stops when the screen goes off some other way.
 */
@Stable
class SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable {
        set(0)
        Controls.sleep()
    }

    /** When the TV goes to sleep ([SystemClock.elapsedRealtime]), or null while the timer is off. */
    var endsAt by mutableStateOf<Long?>(null)
        private set

    /** What it was set to, in minutes (0 while it is off). */
    var minutes by mutableIntStateOf(0)
        private set

    /** Starts the timer for [minutes], or turns it off (0). */
    fun set(minutes: Int) {
        handler.removeCallbacks(expire)
        this.minutes = minutes.coerceAtLeast(0)
        if (minutes <= 0) {
            endsAt = null
            return
        }
        val ms = minutes * 60_000L
        endsAt = SystemClock.elapsedRealtime() + ms
        handler.postDelayed(expire, ms)
    }

    /** What is left, in ms (0 while it is off). */
    fun remaining(): Long = endsAt?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) } ?: 0L

    fun screenOff() = set(0)

    companion object {
        /** The choices, top to bottom as tvOS lists them, then Off. */
        val CHOICES = listOf(120, 60, 30, 15, 0)
    }
}
