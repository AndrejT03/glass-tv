package com.andrej.glasstv.screensaver

import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.ui.TextFont
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AerialSaver"

/** Decoder failures worth retrying on the same video; an unsupported format is not one. */
private val DECODER_HICCUPS = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
)

internal val PoiStyle = TextStyle(
    fontFamily = TextFont,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    color = Color.White.copy(alpha = 0.9f),
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 10f),
)

/**
 * Apple TV aerials streamed from Apple's CDN, shuffled, with the tvOS fade through black between
 * shots and the location line in the corner. The order (which looks at the disk cache, slow to open
 * on the TV) is worked out off the UI thread; the screen stays black with the clock meanwhile.
 */
@Composable
fun AerialSaver(uhd: Boolean, clock: SaverClock, skips: Flow<Int>, fast: Boolean, onFail: () -> Unit) {
    val context = LocalContext.current
    val aerials by produceState<List<Aerial>?>(null) {
        val start = SystemClock.uptimeMillis()
        value = withContext(Dispatchers.IO) { AerialCache.order(context, loadAerials(context)) { aerialUrl(it, uhd) } }
        Log.i(TAG, "Aerials ordered in ${SystemClock.uptimeMillis() - start} ms")
    }
    val list = aerials
    if (list == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { SaverClockOverlay(clock, shot = 0) }
        return
    }
    AerialPlayback(list, uhd, clock, skips, fast, onFail)
}

@OptIn(UnstableApi::class)
@Composable
private fun AerialPlayback(aerials: List<Aerial>, uhd: Boolean, clock: SaverClock, skips: Flow<Int>, fast: Boolean, onFail: () -> Unit) {
    val context = LocalContext.current
    val veil = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var poi by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    // Until the first video frame arrives (a slow network, a cold cache) an aerial still holds
    // the screen, so the saver never opens on black. After a cut, the next shot's own still (when
    // it has one on disk) holds the screen the same way until that video has a picture.
    val stills = GlassApp.instance.wallpapers.stills
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val file = aerials.firstNotNullOfOrNull { stills.cached(it) } ?: return@LaunchedEffect
        val image = decode(file, 1920)
        if (!playing) still = image
    }
    val stillAlpha = remember { Animatable(1f) }
    // The shot on screen has no picture yet (a cut, or a decoder being retried).
    var waiting by remember { mutableStateOf(true) }

    val player = remember { aerialPlayer(context, aerials, uhd) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(player, lifecycle) {
        // Failures since a video last showed a picture.
        var errors = 0
        var cutAt = SystemClock.uptimeMillis()
        // The shot (playlist index) whose video last put its first picture on screen. When a video
        // ends by itself, the decoder carries straight on into the next one, and that one's first
        // picture arrives a moment before the cut is reported: the cut mustn't then wait for a
        // picture that has already come. (It did: with the remote resting, the saver stayed on the
        // second shot's still, or on black, for good.)
        var pictureOf = C.INDEX_UNSET
        // Video frames sent to the screen (counted on the playback thread), and how many there had
        // been at the last cut.
        val frames = AtomicInteger()
        var framesAtCut = 0

        // The shot on screen has a picture: the still or the black in front of it dissolves away.
        fun reveal(fromBlack: Boolean = false) {
            errors = 0
            playing = true
            waiting = false
            scope.launch {
                if (fromBlack) {
                    veil.snapTo(1f)
                    stillAlpha.snapTo(0f)
                }
                if (still != null && stillAlpha.value > 0.01f) {
                    // The still dissolves straight into the moving picture.
                    veil.snapTo(0f)
                    stillAlpha.animateTo(0f, tween(1400, easing = LinearEasing))
                } else {
                    stillAlpha.snapTo(0f)
                    veil.animateTo(0f, tween(1800, easing = LinearEasing))
                }
            }
        }

        // The analytics event says which shot a first picture belongs to; the player's own doesn't.
        val pictures = object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                pictureOf = eventTime.windowIndex
                val id = aerials.getOrNull(eventTime.windowIndex)?.id
                if (eventTime.windowIndex != player.currentMediaItemIndex) {
                    Log.i(TAG, "Picture of the next shot, ahead of its cut ($id)")
                    return
                }
                Log.i(TAG, "Picture ${SystemClock.uptimeMillis() - cutAt} ms after the cut ($id)")
                reveal()
            }
        }

        val listener = object : Player.Listener {
            // A cut, at the end of a shot or by Left/Right: black until the new video has a picture
            // (or its still, if it has one), never the last frame of the old one frozen on screen
            // while the new one loads.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                cutAt = SystemClock.uptimeMillis()
                framesAtCut = frames.get()
                shot++
                if (pictureOf == player.currentMediaItemIndex) {
                    Log.i(TAG, "Cut to ${mediaItem?.mediaId} (reason $reason), its picture already there")
                    reveal(fromBlack = true)
                    return
                }
                Log.i(TAG, "Cut to ${mediaItem?.mediaId} (reason $reason)")
                waiting = true
                val next = aerials.firstOrNull { it.id == mediaItem?.mediaId }
                scope.launch {
                    veil.snapTo(1f)
                    stillAlpha.snapTo(0f)
                    val file = next?.let { stills.cached(it) } ?: return@launch
                    val image = decode(file, 1920) ?: return@launch
                    if (!waiting) return@launch
                    still = image
                    stillAlpha.snapTo(1f)
                    veil.animateTo(0f, tween(500, easing = LinearEasing))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Aerial ${player.currentMediaItem?.mediaId} failed: ${error.errorCodeName}", error)
                waiting = true
                framesAtCut = frames.get()
                if (++errors > 6) {
                    onFail()
                    return
                }
                // The decoder failing (the TV's has moments) is retried on the same video, after a
                // pause for it to recover; a network hiccup (the Wi-Fi waking up) moves on to the next
                // video, after a pause that grows with each failure. If nothing plays at all, the
                // timeout below still hands over to the stills.
                val decoder = error.errorCode in DECODER_HICCUPS
                scope.launch {
                    delay(if (decoder) 1_500L else 1_000L * errors)
                    if (!decoder) player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(pictures)
        player.setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
        // A last guard: a second of video reaching the screen while the shot still counts as
        // waiting means it has a picture after all. Whatever the events do, the saver never stays
        // on a still, or on black, over a video that is playing.
        val guard = scope.launch {
            while (true) {
                delay(500)
                if (waiting && frames.get() - framesAtCut > 30) {
                    Log.w(TAG, "Frames on screen with no picture reported (${player.currentMediaItem?.mediaId})")
                    reveal()
                }
            }
        }
        var released = false
        // Released as soon as the saver is paused, before the home screen (or an app) comes back
        // and starts a decoder of its own: the TV's can't run two 4K ones.
        fun release(why: String) {
            if (released) return
            released = true
            val start = SystemClock.uptimeMillis()
            guard.cancel()
            player.removeListener(listener)
            player.removeAnalyticsListener(pictures)
            player.release()
            Log.i(TAG, "Player released ($why) in ${SystemClock.uptimeMillis() - start} ms")
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) release("pause") }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            release("gone")
        }
    }

    // Left/Right: fade out and cut to the next or previous shot.
    LaunchedEffect(player) {
        skips.collect { step ->
            veil.animateTo(1f, tween(350))
            if (step > 0) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
        }
    }

    // Debug: a new shot every 20 seconds instead of at the end of each video. It jumps to the last
    // seconds of the shot, so the cut itself is the one the saver makes on its own when a video ends
    // (Left/Right are there to try a seek).
    if (fast) {
        LaunchedEffect(player) {
            while (true) {
                delay(20_000)
                if (player.duration > 10_000) player.seekTo(player.duration - 4_000)
            }
        }
    }

    // A video that never starts (no route to Apple's servers) gives way to the stills.
    LaunchedEffect(Unit) {
        delay(25_000)
        if (!playing) onFail()
    }

    // Fade to black before each cut, and track the point-of-interest caption.
    LaunchedEffect(player) {
        var lastText: String? = null
        var shownAt = 0L
        while (true) {
            val duration = player.duration
            val position = player.currentPosition
            if (duration > 0 && duration - position in 1..1700 && veil.targetValue < 1f) {
                launch { veil.animateTo(1f, tween(1200, easing = LinearEasing)) }
            }
            val text = aerials.getOrNull(player.currentMediaItemIndex)?.poi?.lastOrNull { it.first * 1000L <= position }?.second
            val now = System.currentTimeMillis()
            if (text != lastText) {
                lastText = text
                shownAt = now
            }
            poi = text?.takeIf { now - shownAt < 12_000 }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { SurfaceView(it).also { view -> player.setVideoSurfaceView(view) } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }.background(Color.Black))
        still?.let { image ->
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = stillAlpha.value }) { drawCover(image) }
        }
        // The caption belongs to the moving picture: it waits until the shot has one and has faded
        // in (not over a still standing in for it), then dissolves between lines.
        val caption by remember { derivedStateOf { poi.takeIf { !waiting && veil.value < 0.4f && stillAlpha.value < 0.5f } } }
        Crossfade(
            caption,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 36.dp),
            animationSpec = tween(1000),
            label = "poi",
        ) { text ->
            if (text != null) BasicText(text, Modifier.widthIn(max = 700.dp), style = PoiStyle, maxLines = 2)
        }
        SaverClockOverlay(clock, shot)
    }
}

/** The clock over an aerial: like tvOS 26, it changes place with each shot. */
@Composable
private fun SaverClockOverlay(clock: SaverClock, shot: Int) {
    when (clock) {
        SaverClock.Big -> MovingClock(listOf(ClockLayout.Center, ClockLayout.StackRight, ClockLayout.StackLeft)[shot % 3], 1500)
        SaverClock.Small -> SmallClock(shot)
        SaverClock.Off -> Unit
    }
}
