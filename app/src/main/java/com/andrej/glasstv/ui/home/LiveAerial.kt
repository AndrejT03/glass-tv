package com.andrej.glasstv.ui.home

import android.util.Log
import android.view.SurfaceView
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.andrej.glasstv.screensaver.Aerial
import com.andrej.glasstv.screensaver.AerialCache
import com.andrej.glasstv.screensaver.aerialPlayer
import com.andrej.glasstv.screensaver.aerialUrl
import com.andrej.glasstv.screensaver.loadAerials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LiveAerial"

/**
 * Where the live aerial was when the home screen went away (an app, the screen saver), so it
 * carries on there when it comes back: that part is already in the disk cache, so it starts at
 * once instead of waiting for a new video from the network.
 */
private object LiveResume {
    var id: String? = null
    var position = 0L
}

/** How quickly the still covers the video when the remote is used. */
const val STILL_FADE_MS = 250

/**
 * Apple Aerial as a live wallpaper behind the big-clock home: Apple's aerials playing one after
 * another, dipping through black between shots like the screen saver. [onPlaying] says when there is
 * a picture (until then, and if the videos can't be reached, the day's still stays on screen).
 *
 * The glass on top (the dock, the clock, the capsule) samples a backdrop rather than the live
 * picture. Only aerials with a hand-picked wallpaper frame play here, and [onShot] says which one is
 * on screen, so that frame becomes the backdrop and the glass shows the colours of the shot behind it.
 * (Copying frames off the video surface was tried: the TV's decoder writes a format that comes back
 * as noise.)
 *
 * The video is on its own surface below the window. On the TV that surface is composited on the GPU
 * (its display hardware takes no scaled layers), which nearly doubles the cost of every frame of
 * the launcher. So it plays only while the remote rests: when [active] turns false (a key, or the
 * app grid covering the screen) [onPlaying] reports false at once, the shot's still fades in over
 * the video, and then the video pauses and its surface goes away; when [active] comes back it
 * returns behind the still and the still fades out once it has a picture again.
 */
@Composable
fun LiveAerial(uhd: Boolean, active: Boolean, onPlaying: (Boolean) -> Unit, onShot: (Aerial) -> Unit) {
    val context = LocalContext.current
    // Worked out off the UI thread: the order looks at the disk cache, slow to open on the TV.
    val aerials by produceState<List<Aerial>?>(null) {
        value = withContext(Dispatchers.IO) {
            // The ones with a hand-picked wallpaper frame: the calm, wide shots that suit a home
            // screen. The one that was playing goes first, to carry on where it was.
            AerialCache.order(context, loadAerials(context).filter { it.still != null }) { aerialUrl(it, uhd) }
                .sortedBy { if (it.id == LiveResume.id) 0 else 1 }
        }
    }
    aerials?.let { LivePlayback(it, uhd, active, onPlaying, onShot) }
}

@Composable
private fun LivePlayback(aerials: List<Aerial>, uhd: Boolean, active: Boolean, onPlaying: (Boolean) -> Unit, onShot: (Aerial) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shot by rememberUpdatedState(onShot)
    val playing by rememberUpdatedState(onPlaying)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember {
        aerialPlayer(context, aerials, uhd).apply {
            if (aerials.firstOrNull()?.id == LiveResume.id) seekTo(0, LiveResume.position)
        }
    }
    val veil = remember { Animatable(1f) }
    val on by rememberUpdatedState(active)
    var surfaceShown by remember { mutableStateOf(true) }

    fun current() = aerials.firstOrNull { it.id == player.currentMediaItem?.mediaId }?.let(shot)

    DisposableEffect(player, lifecycle) {
        var errors = 0
        var shown = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                errors = 0
                if (!shown) {
                    shown = true
                    current()
                }
                // Also after the surface comes back: the still fades out over the picture again.
                if (on) playing(true)
                scope.launch { veil.animateTo(0f, tween(1400, easing = LinearEasing)) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                scope.launch {
                    veil.snapTo(1f)
                    delay(250)
                    veil.animateTo(0f, tween(1600, easing = LinearEasing))
                }
                if (shown) current()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Aerial ${player.currentMediaItem?.mediaId} failed: ${error.errorCodeName}")
                // No network: the still stays. Otherwise try the next video after a growing pause.
                if (++errors > 6) {
                    shown = false
                    playing(false)
                    return
                }
                scope.launch {
                    delay(1_000L * errors)
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        }
        player.addListener(listener)
        var released = false
        // Released the moment the home screen is paused, before the screen saver or an app starts a
        // decoder of its own: the TV's can't run two 4K ones, and the next one failed to start.
        fun release() {
            if (released) return
            released = true
            LiveResume.id = player.currentMediaItem?.mediaId
            LiveResume.position = player.currentPosition
            player.removeListener(listener)
            player.release()
            playing(false)
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) release() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            release()
        }
    }

    LaunchedEffect(active) {
        if (active) {
            surfaceShown = true
            player.playWhenReady = true
        } else {
            playing(false)
            // Until the still has faded in over it.
            delay(STILL_FADE_MS + 50L)
            player.playWhenReady = false
            surfaceShown = false
        }
    }

    // Dip to black before each cut.
    LaunchedEffect(player) {
        while (true) {
            val left = player.duration - player.currentPosition
            if (player.duration > 0 && left in 1..1300 && veil.targetValue < 1f) {
                launch { veil.animateTo(1f, tween(1100, easing = LinearEasing)) }
            }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { SurfaceView(it).also { view -> player.setVideoSurfaceView(view) } },
            modifier = Modifier.fillMaxSize(),
            update = { it.visibility = if (surfaceShown) View.VISIBLE else View.INVISIBLE },
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }.background(Color.Black))
    }
}
