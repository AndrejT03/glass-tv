package com.andrej.glasstv.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.andrej.glasstv.R
import com.andrej.glasstv.data.Prefs

/**
 * The soft focus tick and select pop that give the remote its tactile feel.
 *
 * Played from a thread of their own: SoundPool.play waits for the audio server, and on the TV the
 * first press after a pause waited ~70 ms for it to set a track up (a few ms on every later press),
 * holding the focus back from moving until it had.
 */
class Sounds(context: Context, private val prefs: Prefs) {
    private val thread = HandlerThread("sounds", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val tick = pool.load(context, R.raw.tick, 1)
    private val pop = pool.load(context, R.raw.pop, 1)

    fun tick() {
        if (prefs.sounds) handler.post { pool.play(tick, 0.32f, 0.32f, 1, 0, 1f) }
    }

    fun select() {
        if (prefs.sounds) handler.post { pool.play(pop, 0.4f, 0.4f, 1, 0, 1f) }
    }

    fun release() {
        handler.post { pool.release() }
        thread.quitSafely()
    }
}
