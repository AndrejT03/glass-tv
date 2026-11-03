package com.andrej.glasstv.screensaver

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.ui.LocalStrings
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The Dynamic Wallpaper setting's choice that shows each picture in turn. */
const val DYNAMIC_ALL = "all"

/** In turn: the next picture every five minutes, at the same time of day. */
private const val TURN_MS = 5 * 60_000L

/** A new frame settles from a little closer over half a minute; then nothing moves until the next. */
private const val SETTLE_FROM = 1.045f
private const val SETTLE_MS = 30_000

/** The sun moving on to the next frame: a slow dissolve, as on the Mac. */
private const val SUN_DISSOLVE_MS = 4_000

/** Debug fast mode: an hour of the day every four seconds, so a day goes by in a minute and a half. */
private const val FAST_DAY = 900L

/**
 * "Dynamic Wallpaper": Apple's dynamic desktop pictures (Big Sur, Catalina, The Beach…), each showing
 * the frame for where the sun is now, as the Mac does through the day: the sun moving on dissolves
 * slowly into the next frame. The same screen as the other savers: the picture filling it, the big
 * clock moving between the middle and either side with each new frame or picture, and in the bottom
 * corner the picture's name and the part of the day, for a while after each change. Left/Right step to
 * the previous or next picture; [choice] is one of them or [DYNAMIC_ALL], each in turn.
 *
 * Between changes the screen is still (a frame settles in over half a minute, then nothing animates),
 * so the TV's GPU rests while the saver is on.
 */
@Composable
fun DynamicSaver(choice: String, clock: SaverClock, skips: Flow<Int>, fast: Boolean, onEmpty: () -> Unit) {
    val store = GlassApp.instance.dynamic
    val strings = LocalStrings.current
    val rotate = choice == DYNAMIC_ALL
    var all by remember { mutableStateOf(emptyList<DynamicWallpaper>()) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val read = store.load()
        if (read.isEmpty()) return@LaunchedEffect onEmpty()
        index = if (rotate) Random.nextInt(read.size) else read.indexOfFirst { it.id == choice }.coerceAtLeast(0)
        all = read
    }
    val photos = remember { Photos(width = 1920, keep = 4) }
    var stepped by remember { mutableStateOf(false) }
    var shots by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<Still?>(null) }
    var leaving by remember { mutableStateOf<Still?>(null) }
    val fade = remember { Animatable(1f) }

    // The time the frames follow: now, once a minute; in fast mode a day going by in 96 seconds.
    val start = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(start) }
    LaunchedEffect(fast) {
        while (true) {
            val real = System.currentTimeMillis()
            now = if (fast) start + (real - start) * FAST_DAY else real
            delay(if (fast) 1_000 else 60_000 - real % 60_000)
        }
    }

    val wallpaper = all.getOrNull(index.mod(all.size.coerceAtLeast(1)))
    val sun = sunAt(now)
    val frame = wallpaper?.frameFor(sun)

    // A new frame dissolves in only once it is decoded, over the one on screen: two layers at most.
    LaunchedEffect(wallpaper, frame) {
        if (wallpaper == null || frame == null) return@LaunchedEffect
        val image = photos.get(store.tvFrame(wallpaper, frame)) ?: return@LaunchedEffect
        // Stepping again mid-dissolve: whichever is more on screen is the one that leaves.
        leaving = if (fade.value < 0.5f && leaving != null) leaving else shown
        val sunMoved = leaving?.wallpaper === wallpaper
        val still = Still(image, wallpaper, LAYOUTS[shots.mod(LAYOUTS.size)], "${wallpaper.name}  ·  ${strings.dayParts[DayPart.of(sun).ordinal]}")
        shots++
        shown = still
        launch { still.settle.animateTo(1f, tween(SETTLE_MS, easing = LinearOutSlowInEasing)) }
        // The neighbours' frames for now, so Left/Right and the next turn show at once.
        launch {
            for (step in intArrayOf(1, -1)) {
                val next = all[(index + step).mod(all.size)]
                photos.get(store.tvFrame(next, next.frameFor(sun)))
            }
        }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(if (stepped) 900 else if (sunMoved) SUN_DISSOLVE_MS else 1800))
        stepped = false
        leaving = null
    }
    LaunchedEffect(rotate, index) {
        if (!rotate) return@LaunchedEffect
        delay(if (fast) 8_000 else TURN_MS)
        index++
    }
    LaunchedEffect(Unit) {
        skips.collect {
            stepped = true
            index += it
        }
    }
    // The name and the part of the day, for a while after each change.
    var caption by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shown) {
        caption = shown?.caption
        delay(12_000)
        caption = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        leaving?.let { Frame(it) }
        shown?.let { Frame(it) { fade.value } }
        Crossfade(
            caption,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 36.dp),
            animationSpec = tween(1000),
            label = "dynamicCaption",
        ) { text ->
            if (text != null) BasicText(text, style = PoiStyle, maxLines = 1)
        }
        when (clock) {
            // As in Landscapes: the clock moves in the same dissolve as the picture.
            SaverClock.Big -> {
                val from = leaving?.layout
                val to = shown?.layout
                if (from != null && from != to) FadingClock(from) { 1f - fade.value }
                if (to != null) FadingClock(to) { if (from == null || from == to) 1f else fade.value }
            }
            SaverClock.Small -> SmallClock(shots)
            SaverClock.Off -> Unit
        }
    }
}

/** A frame on screen: which picture it is, where the clock goes with it, and its caption. */
private class Still(val image: ImageBitmap, val wallpaper: DynamicWallpaper, val layout: ClockLayout, val caption: String) {
    val settle = Animatable(SETTLE_FROM)
}

@Composable
private fun Frame(still: Still, alpha: () -> Float = { 1f }) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = still.settle.value
                scaleX = s
                scaleY = s
                this.alpha = alpha()
                // One picture: faded as it is drawn, without an offscreen copy (the TV's tiled GPU).
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        drawCover(still.image)
    }
}
