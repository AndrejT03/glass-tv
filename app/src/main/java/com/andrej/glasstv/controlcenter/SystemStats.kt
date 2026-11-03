package com.andrej.glasstv.controlcenter

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.sin

/**
 * A moment of the TV's performance, for the control center's live tile.
 *
 * @param pingMs how long the internet takes to answer, in milliseconds: null before the first try,
 *   [NO_ANSWER] when it didn't answer within a second.
 * @param downMbps what the TV is receiving over the network, in megabits a second (null until a
 *   second reading).
 */
@Immutable
data class Stats(
    val pingMs: Int? = null,
    val ram: Float = 0f,
    val ramUsedGb: Float = 0f,
    val ramTotalGb: Float = 0f,
    val downMbps: Float? = null,
)

/**
 * Reads the TV's performance: how fast the internet answers, its memory and how fast the network is
 * bringing data in, all open to any app. The processor's load isn't: Android closes /proc/stat and
 * /proc/uptime to apps, and the TCL has no cpuidle counters, runs its cores at full clock and keeps
 * its temperature from adb too.
 */
class SystemStats(context: Context) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val memory = ActivityManager.MemoryInfo()

    /** Debug builds only: made-up figures, for the emulator. */
    var demo = false

    private var lastRx = -1L
    private var lastAt = 0L

    /** One reading; the network's speed is worked out against the reading before. */
    @Synchronized
    fun sample(): Stats {
        if (demo) return made()
        activity.getMemoryInfo(memory)
        val total = memory.totalMem.toFloat()
        val used = (memory.totalMem - memory.availMem).toFloat()
        val ping = ping()
        val rx = TrafficStats.getTotalRxBytes()
        val now = SystemClock.elapsedRealtime()
        val seconds = (now - lastAt) / 1000f
        val down = if (rx == TrafficStats.UNSUPPORTED.toLong() || lastRx < 0 || seconds <= 0f) null
            else (rx - lastRx).coerceAtLeast(0) * 8f / 1_000_000f / seconds
        lastRx = rx
        lastAt = now
        return Stats(
            pingMs = ping,
            ram = if (total > 0f) used / total else 0f,
            ramUsedGb = used / GB,
            ramTotalGb = total / GB,
            downMbps = down,
        )
    }

    /**
     * One round trip to the internet: opening a connection to Cloudflare's or Google's public DNS
     * (by address, so no name lookup is timed), which answer from close by. Called off the UI thread.
     */
    private fun ping(): Int {
        for (host in PING_HOSTS) {
            val start = SystemClock.elapsedRealtimeNanos()
            val answered = runCatching { Socket().use { it.connect(InetSocketAddress(host, 443), PING_TIMEOUT_MS) } }.isSuccess
            if (answered) return ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).toInt().coerceAtLeast(1)
        }
        return NO_ANSWER
    }

    private fun made(): Stats {
        val t = SystemClock.elapsedRealtime() / 1000f
        return Stats(
            pingMs = (14f + 4f * sin(t * 1.3f)).toInt(),
            ram = 0.63f + 0.02f * sin(t * 0.3f),
            ramUsedGb = 1.13f + 0.04f * sin(t * 0.3f),
            ramTotalGb = 1.8f,
            downMbps = (14f + 9f * sin(t * 0.6f) + 3f * sin(t * 2.1f)).coerceAtLeast(0f),
        )
    }

    companion object {
        /** [Stats.pingMs] when the internet didn't answer. */
        const val NO_ANSWER = -1

        private const val GB = 1024f * 1024f * 1024f
        private const val PING_TIMEOUT_MS = 1_000
        private val PING_HOSTS = listOf("1.1.1.1", "8.8.8.8")
    }
}
