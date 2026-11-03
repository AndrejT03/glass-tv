package com.andrej.glasstv.screensaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.andrej.glasstv.GlassApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal val LAYOUTS = listOf(ClockLayout.Center, ClockLayout.StackRight, ClockLayout.StackLeft)

/** The Apple Aerial stills on disk, growing as the rest download in the background. */
@Composable
private fun rememberShots(onEmpty: () -> Unit): List<File> {
    val stills = GlassApp.instance.wallpapers.stills
    var files by remember { mutableStateOf(stills.cachedFiles().shuffled()) }
    LaunchedEffect(Unit) {
        if (files.isEmpty() && !isOnline(GlassApp.instance)) onEmpty()
        stills.fetchMissing { files = files + it }
        if (files.isEmpty()) onEmpty()
    }
    return files
}

/**
 * tvOS 26 "Landscapes": full-screen photographs that slowly push in and dissolve into each other,
 * with the big clock moving between the middle and either side.
 */
@Composable
fun LandscapesSaver(clock: SaverClock, skips: Flow<Int>, fast: Boolean, onEmpty: () -> Unit) {
    val shots = rememberShots(onEmpty)
    val photos = remember { Photos(width = 1920, keep = 4) }
    var index by remember { mutableIntStateOf(0) }
    var stepped by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Shot?>(null) }
    var leaving by remember { mutableStateOf<Shot?>(null) }
    val fade = remember { Animatable(1f) }
    // The push-in follows the frame clock, so a photo keeps moving the same way through a dissolve.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = it } }

    // The next photo dissolves in only once it is decoded, over the one on screen: never more than
    // two layers, and never a fade to black while the TV decodes. Its neighbours are decoded ahead,
    // so Left/Right step at once. A new photo every 14 seconds; a step starts the wait over.
    LaunchedEffect(shots.isNotEmpty(), index) {
        if (shots.isEmpty()) return@LaunchedEffect
        val n = shots.size
        val file = shots[index.mod(n)]
        val image = photos.get(file) ?: return@LaunchedEffect
        val start = withFrameMillis { it }
        // Stepping again mid-dissolve: whichever photo is more on screen is the one that leaves.
        leaving = if (fade.value < 0.5f && leaving != null) leaving else shown
        shown = Shot(image, if (file.name.hashCode() % 2 == 0) 1f else -1f, start, LAYOUTS[index.mod(LAYOUTS.size)])
        launch {
            photos.get(shots[(index + 1).mod(n)])
            photos.get(shots[(index - 1).mod(n)])
        }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(if (stepped) 700 else 1800))
        stepped = false
        leaving = null
        delay(if (fast) 4_000 else 14_000)
        index++
    }
    LaunchedEffect(Unit) {
        skips.collect {
            stepped = true
            index += it
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        leaving?.let { PushIn(it, { now }) }
        shown?.let { PushIn(it, { now }, { fade.value }) }
        when (clock) {
            // The clock moves with the photos, in the same dissolve: two layers at most, however fast
            // Left/Right are pressed (each clock is a full-screen layer).
            SaverClock.Big -> {
                val from = leaving?.layout
                val to = shown?.layout
                if (from != null && from != to) FadingClock(from) { 1f - fade.value }
                if (to != null) FadingClock(to) { if (from == null || from == to) 1f else fade.value }
            }
            SaverClock.Small -> SmallClock(index)
            SaverClock.Off -> Unit
        }
    }
}

/** A photo on screen: drifting left or right as it pushes in, from the frame it appeared on; where the clock goes. */
private class Shot(val image: ImageBitmap, val drift: Float, val start: Long, val layout: ClockLayout)

/** Ken Burns: the photo slowly pushes in over 17 seconds and drifts a little sideways. */
@Composable
private fun PushIn(shot: Shot, now: () -> Long, alpha: () -> Float = { 1f }) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val t = ((now() - shot.start) / 17_000f).coerceIn(0f, 1f)
                val s = 1.02f + 0.07f * t
                scaleX = s
                scaleY = s
                translationX = shot.drift * 18.dp.toPx() * t
                this.alpha = alpha()
            },
    ) {
        drawCover(shot.image)
    }
}

/**
 * Decoded photos kept around the ones on screen, so stepping with Left/Right and moving on to the
 * next photo show a picture that is already decoded (a 4K still takes the TV a few hundred ms).
 */
internal class Photos(private val width: Int, private val keep: Int) {
    private val decoded = object : LinkedHashMap<File, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<File, ImageBitmap>?) = size > keep
    }
    private val lock = Mutex()

    suspend fun get(file: File): ImageBitmap? = lock.withLock {
        decoded[file] ?: decode(file, width)?.also { decoded[file] = it }
    }
}

/** The black seam between two photos of the Portraits row while it glides. */
private const val SEAM = 20f

/** Ease in and out: the row starts and settles gently. */
private val Glide = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/** A new portrait every minute, and the clock to its next place with every third: every three minutes. */
private const val PORTRAIT_MS = 60_000L
private const val CLOCK_EVERY = 3

/**
 * "Portraits": the same screen as the other savers (a photo filling it, the big clock moving
 * between the middle and either side, the picker's caption at the bottom), but the photos sit side
 * by side in a row: every minute the row glides on to the next one, and Left/Right glide it either
 * way at once. The clock moves with every third photo, every three minutes.
 */
@Composable
fun PortraitsSaver(clock: SaverClock, skips: Flow<Int>, fast: Boolean, onEmpty: () -> Unit) {
    val shots = rememberShots(onEmpty)
    val photos = remember { Photos(width = 1920, keep = 4) }
    var index by remember { mutableIntStateOf(0) }
    // How far (0..1) the row has glided from the photo at [index] towards the next one.
    val slide = remember { Animatable(0f) }
    val gliding by remember { derivedStateOf { slide.value > 0f } }
    // The photos either side are decoded ahead, so a glide never brings in an empty page.
    LaunchedEffect(shots.isNotEmpty(), index) {
        if (shots.isEmpty()) return@LaunchedEffect
        photos.get(shots[(index + 1).mod(shots.size)])
        photos.get(shots[(index - 1).mod(shots.size)])
    }
    LaunchedEffect(shots.isNotEmpty()) {
        if (shots.isEmpty()) return@LaunchedEffect
        val steps = Channel<Int>(Channel.CONFLATED)
        launch { skips.collect { steps.trySend(it) } }
        while (true) {
            val step = withTimeoutOrNull(if (fast) 4_000L else PORTRAIT_MS) { steps.receive() }
            // A calm glide on its own, a quick one for Left/Right.
            val glide = if (step == null) tween<Float>(2400, easing = Glide) else spring(dampingRatio = 1f, stiffness = 160f)
            if (step == null || step > 0) {
                slide.animateTo(1f, glide)
                index++
                slide.snapTo(0f)
            } else {
                index--
                slide.snapTo(1f)
                slide.animateTo(0f, glide)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (shots.isNotEmpty()) {
            for (k in 0..1) {
                // Keyed by absolute position, so a page keeps its decoded photo as it glides along.
                key(index + k) { Page(shots[(index + k).mod(shots.size)], photos, k) { slide.value } }
            }
        }
        // The clock keeps its place for three photos, then moves to the next in a dissolve that
        // follows that glide.
        val place = Math.floorDiv(index, CLOCK_EVERY)
        val moving = Math.floorDiv(index + 1, CLOCK_EVERY) != place
        when (clock) {
            SaverClock.Big -> {
                FadingClock(LAYOUTS[place.mod(LAYOUTS.size)]) { if (moving) 1f - slide.value else 1f }
                if (gliding && moving) FadingClock(LAYOUTS[(place + 1).mod(LAYOUTS.size)]) { slide.value }
            }
            SaverClock.Small -> SmallClock(place)
            SaverClock.Off -> Unit
        }
    }
}

/** One photo of the Portraits row, filling the screen wherever the glide has it. */
@Composable
private fun Page(file: File, photos: Photos, k: Int, slide: () -> Float) {
    val image by produceState<ImageBitmap?>(null, file) { value = photos.get(file) }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = (k - slide()) * (size.width + SEAM.dp.toPx()) },
    ) {
        // Only while some of it is on screen.
        if (abs(k - slide()) < 1f) image?.let { drawCover(it) }
    }
}

internal suspend fun decode(file: File, width: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })?.let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.copy(Bitmap.Config.HARDWARE, false) else it
    }?.asImageBitmap()
}

internal fun DrawScope.drawCover(image: ImageBitmap) {
    val scale = max(size.width / image.width, size.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        filterQuality = FilterQuality.Low,
    )
}
