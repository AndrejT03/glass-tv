package com.andrej.glasstv.screensaver

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo as CodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/** One Apple TV aerial (tvOS feed, bundled in assets/aerials.json by tools/build_aerials.py). */
class Aerial(
    val id: String,
    val name: String,
    val category: String,
    /** (second, description) pairs; tvOS shows the matching line as the shot moves. */
    val poi: List<Pair<Int, String>>,
    /** 10-bit HEVC, 1080p. */
    val hd: String,
    /** 10-bit HEVC, 4K. */
    val uhd: String,
    /** 8-bit H.264, 1080p, for decoders without HEVC Main10. */
    val avc: String,
    /** Position (0..1) of a hand-picked frame that works as a home wallpaper, if any. */
    val still: Float?,
)

fun loadAerials(context: Context): List<Aerial> {
    val json = context.assets.open("aerials.json").bufferedReader().use { it.readText() }
    val array = JSONArray(json)
    return List(array.length()) { i ->
        val o = array.getJSONObject(i)
        val poi = o.getJSONArray("poi")
        Aerial(
            id = o.getString("id"),
            name = o.getString("name"),
            category = o.getString("category"),
            poi = List(poi.length()) { j -> poi.getJSONArray(j).let { it.getInt(0) to it.getString(1) } },
            hd = o.getString("hd"),
            uhd = o.getString("uhd"),
            avc = o.getString("avc"),
            still = if (o.has("still")) o.getDouble("still").toFloat() else null,
        )
    }
}

/** Apple's "SDR" aerials are 10-bit HEVC; use them only where a hardware decoder handles Main10. */
fun aerialUrl(aerial: Aerial, wantUhd: Boolean): String = when {
    wantUhd && hardwareHevcMain10(3840, 2160) -> aerial.uhd
    hardwareHevcMain10(1920, 1080) -> aerial.hd
    else -> aerial.avc
}

private val main10Cache = HashMap<Int, Boolean>()

private fun hardwareHevcMain10(width: Int, height: Int): Boolean = main10Cache.getOrPut(width) {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder &&
            (Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated) &&
            info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) } &&
            runCatching {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } &&
                    caps.videoCapabilities?.isSizeSupported(width, height) == true
            }.getOrDefault(false)
    }
}

/**
 * Disk cache for aerials, like Apple TV keeps its recent ones: replays start instantly and play
 * without the network. Least recently played videos are evicted first.
 */
@OptIn(UnstableApi::class)
object AerialCache {
    private const val MAX_BYTES = 768L * 1024 * 1024
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "aerials"),
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }

    /** Mostly-downloaded videos first (in random order), then the rest, so the saver usually starts instantly. */
    fun order(context: Context, aerials: List<Aerial>, url: (Aerial) -> String): List<Aerial> {
        val c = get(context)
        val (cached, rest) = aerials.shuffled().partition { c.getCachedBytes(url(it), 0, Long.MAX_VALUE) > 64L * 1024 * 1024 }
        return cached + rest
    }
}

/**
 * A muted player that goes through [aerials] in a loop, reading through the disk cache, for the
 * screen saver and the live wallpaper. Its video decoder is the one [AerialRenderers] sets up:
 * kept from shot to shot, and never alongside another one.
 */
@OptIn(UnstableApi::class)
fun aerialPlayer(context: Context, aerials: List<Aerial>, uhd: Boolean): ExoPlayer {
    val upstream = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    val cached = CacheDataSource.Factory()
        .setCache(AerialCache.get(context))
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    return ExoPlayer.Builder(context, AerialRenderers(context))
        .setMediaSourceFactory(DefaultMediaSourceFactory(cached))
        // Keep the buffer small: the TV has under 1 GB of free RAM, and a 4K aerial is ~25 Mbit/s.
        // Start once 1.2 s is in: a new aerial comes from Apple's CDN, and waiting for 2.5 s left
        // Left/Right on black for seconds.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 20_000, 1_200, 2_500)
                .setTargetBufferBytes(40 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            setMediaItems(aerials.map { MediaItem.Builder().setUri(aerialUrl(it, uhd)).setMediaId(it.id).build() })
            // The next aerial's first seconds load in the background, so Right (and the next shot)
            // starts at once instead of waiting for the network.
            preloadConfiguration = ExoPlayer.PreloadConfiguration(6_000_000L)
            addAnalyticsListener(DecoderLog)
            prepare()
            playWhenReady = true
        }
}

/**
 * The video renderer for aerials. The TV's Realtek decoder fails (error 0x80001000) when a new 4K
 * instance starts while the previous one is still being torn down, and then keeps failing for every
 * new instance: the aerials went black, or stopped on a still with their caption showing. ExoPlayer
 * replaced the decoder at every cut, because the next video's largest sample no longer fit the
 * buffers the decoder was set up with. So the decoder is set up with room for any aerial of its
 * size (which lets ExoPlayer keep it from shot to shot), and [DecoderGate] keeps any replacement (a
 * new saver, the home screen's live aerial) from overlapping the one it replaces.
 */
@OptIn(UnstableApi::class)
private class AerialRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out += RoomyVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setMediaCodecSelector(mediaCodecSelector)
                .setCodecAdapterFactory(DecoderGate.wrap(codecAdapterFactory))
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
        )
    }
}

@OptIn(UnstableApi::class)
private class RoomyVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun getCodecMaxValues(codecInfo: CodecInfo, format: Format, streamFormats: Array<Format>): CodecMaxValues {
        val base = super.getCodecMaxValues(codecInfo, format, streamFormats)
        // All aerials of a session are one size: 4K, or 1080p.
        val uhd = format.width > 1920 || format.height > 1080
        return CodecMaxValues(
            max(base.width, if (uhd) 3840 else 1920),
            max(base.height, if (uhd) 2160 else 1080),
            max(base.inputSize, if (uhd) 4 * 1024 * 1024 else 2 * 1024 * 1024),
        )
    }
}

/**
 * One hardware video decoder at a time, with a moment between them: a new one starts only after
 * the last one has been released and has had [SETTLE_MS] to go away. Waits on ExoPlayer's playback
 * thread, never on the UI's.
 */
@OptIn(UnstableApi::class)
internal object DecoderGate {
    private const val SETTLE_MS = 300L
    private const val MAX_WAIT_MS = 4_000L
    private val lock = ReentrantLock()
    private val freed = lock.newCondition()
    private var open = 0
    private var freedAt = 0L

    fun wrap(base: MediaCodecAdapter.Factory) = MediaCodecAdapter.Factory { configuration ->
        acquire()
        try {
            Gated(base.createAdapter(configuration))
        } catch (e: Throwable) {
            free()
            throw e
        }
    }

    private fun acquire() {
        val settle = lock.withLock {
            val deadline = SystemClock.uptimeMillis() + MAX_WAIT_MS
            while (open > 0) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) {
                    Log.w(TAG, "A video decoder is still open after $MAX_WAIT_MS ms; starting another anyway")
                    break
                }
                freed.await(left, TimeUnit.MILLISECONDS)
            }
            open++
            freedAt + SETTLE_MS - SystemClock.uptimeMillis()
        }
        if (settle > 0) Thread.sleep(settle)
    }

    private fun free() = lock.withLock {
        open = max(0, open - 1)
        freedAt = SystemClock.uptimeMillis()
        freed.signalAll()
    }

    private class Gated(private val codec: MediaCodecAdapter) : MediaCodecAdapter by codec {
        private var released = false

        override fun release() {
            try {
                codec.release()
            } finally {
                if (!released) {
                    released = true
                    free()
                }
            }
        }

        override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener) =
            codec.registerOnBufferAvailableListener(listener)

        override fun <E : Exception> useBuffer(block: ThrowingRunnable<E>) = codec.useBuffer(block)
    }
}

/** What the decoder does at each cut: kept, reconfigured, or replaced, and why. */
@OptIn(UnstableApi::class)
private object DecoderLog : AnalyticsListener {
    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        val e = decoderReuseEvaluation ?: return
        val kept = e.result != DecoderReuseEvaluation.REUSE_RESULT_NO
        Log.i(TAG, "Decoder ${if (kept) "kept" else "replaced"} for ${format.width}x${format.height} (result ${e.result}, reasons 0x${Integer.toHexString(e.discardReasons)})")
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.i(TAG, "Decoder $decoderName up in $initializationDurationMs ms")
    }
}

private const val TAG = "Aerials"

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
