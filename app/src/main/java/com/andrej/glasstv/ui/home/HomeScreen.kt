package com.andrej.glasstv.ui.home

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.controlcenter.controlShadeAt
import com.andrej.glasstv.quicksettings.quickShadeAt
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.formatDate
import com.andrej.glasstv.ui.formatTime
import com.andrej.glasstv.ui.glass.Backdrop
import com.andrej.glasstv.ui.glass.BackdropTransition
import com.andrej.glasstv.ui.glass.LocalBackdrop
import com.andrej.glasstv.ui.glass.LocalBackdropTransition
import com.andrej.glasstv.ui.glass.SHADE
import com.andrej.glasstv.ui.glass.WallpaperStore
import com.andrej.glasstv.ui.glass.backdropOf
import com.andrej.glasstv.ui.glass.liquidGlass
import com.andrej.glasstv.ui.glass.rememberBackdropTransition
import com.andrej.glasstv.ui.rememberMinute
import com.andrej.glasstv.ui.stringsFor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long the focus rests on an app before its Top Shelf dissolves in. */
private const val SHELF_DELAY_MS = 110L

/** How long the remote rests before the live aerial plays again. */
private const val REST_MS = 2_500L

@Composable
fun HomeRoot(c: HomeController) {
    val glass = GlassApp.instance
    val prefs = glass.prefs
    val tvos = prefs.homeStyle != "clock"
    // Re-checked every hour, so the Apple Aerial wallpaper moves on to the next shot each day.
    val now by rememberMinute()
    val hour = now / 3_600_000
    LaunchedEffect(prefs.wallpaper, hour) { glass.wallpapers.load(prefs.wallpaper) }
    LaunchedEffect(Unit) { glass.apps.reload() }

    // tvOS: the focused app's Top Shelf fills the screen. A short pause lets fast scrolling through
    // the dock skip the shelves it only passes over and the lens get most of its glide done first
    // (on the TV's GPU a dissolve nearly doubles the cost of a frame).
    val dock = c.apps.take(Grid.COLS)
    // Only the tvOS home shows shelves; behind the big clock they would only take memory.
    LaunchedEffect(tvos, dock.map { it.key }) {
        if (tvos) glass.topShelf.prepare(dock.sortedBy { if (it.key == c.shelfApp()?.key) 0 else 1 })
    }
    // Followed outside composition: read here, every move of the focus recomposed the home root.
    val shelf by produceState<Backdrop?>(null, tvos) {
        if (!tvos) return@produceState
        snapshotFlow { c.shelfApp() }
            .distinctUntilChangedBy { it?.key to it?.updated }
            .collectLatest { app ->
                app ?: return@collectLatest
                delay(SHELF_DELAY_MS)
                glass.topShelf.backdrop(app)?.let { value = it }
            }
    }

    // Apple Aerial behind the big clock plays the aerials themselves while the home screen is in
    // front (resumed: it gives the decoder up as soon as anything else comes up).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val live = !tvos && prefs.wallpaper == WallpaperStore.AERIAL && resumed
    var liveFrame by remember { mutableStateOf<Backdrop?>(null) }
    var livePlaying by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        if (!live) liveFrame = null
    }
    val scope = rememberCoroutineScope()
    val frames = remember { intArrayOf(0) }

    val backdrop = if (tvos) shelf ?: glass.wallpapers.backdrop else liveFrame ?: glass.wallpapers.backdrop

    val transition = rememberBackdropTransition(backdrop)
    CompositionLocalProvider(
        LocalBackdrop provides backdrop,
        LocalBackdropTransition provides transition,
        LocalStrings provides stringsFor(prefs.language),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (live) {
                // The video plays only while the remote rests (LiveAerial says why).
                var resting by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    snapshotFlow { c.lastInput }.collectLatest {
                        resting = false
                        delay(REST_MS)
                        resting = true
                    }
                }
                val inDock by remember { derivedStateOf { c.focusedAppIndex() < Grid.COLS && c.panel == Panel.None } }
                LiveAerial(
                    uhd = prefs.aerialQuality == "uhd",
                    active = resting && inDock,
                    onPlaying = { livePlaying = it },
                    onShot = { aerial ->
                        scope.launch {
                            val stills = glass.wallpapers.stills
                            val file = stills.cached(aerial) ?: stills.fetch(aerial) ?: return@launch
                            val id = "live:${aerial.id}:${frames[0]++}"
                            withContext(Dispatchers.Default) {
                                BitmapFactory.decodeFile(file.path)?.let { backdropOf(id, it) }
                            }?.let { liveFrame = it }
                        }
                    },
                )
            }
            HomeScreen(c, bigClock = !tvos, glassClock = prefs.clockStyle == "glass", liveVideo = live && livePlaying)
        }
    }
}

@Composable
fun HomeScreen(c: HomeController, bigClock: Boolean, glassClock: Boolean, liveVideo: Boolean = false) {
    val apps = c.apps
    // Only the row is read here: the home screen recomposes when the focus changes rows, and a move
    // along a row recomposes just the two icons it concerns (HomeTile) and nothing else.
    val row by remember(c) { derivedStateOf { c.focusedAppIndex().let { if (it >= 0) it / Grid.COLS else -1 } } }
    val scroll = animateFloatAsState(Grid.scrollFor(row), Motion.Scroll, label = "scroll")
    val grid = animateFloatAsState(if (row >= 1) 1f else 0f, tween(520, easing = Motion.EaseOut), label = "grid")
    val panelOpen = animateFloatAsState(if (c.panel != Panel.None) 1f else 0f, tween(200), label = "panelOpen")
    val focusRequester = remember { FocusRequester() }
    val shadow = remember { makeTileShadow() }
    val zoom = remember { Animatable(1f) }
    // Keyed on Unit: clearing `returning` must not restart the effect, or it cancels the zoom-out
    // halfway and leaves the home screen stuck 7% too big.
    LaunchedEffect(Unit) {
        snapshotFlow { c.returning }.filter { it }.collectLatest {
            zoom.snapTo(RETURN_ZOOM)
            c.returning = false
            zoom.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 160f))
        }
    }
    // The backdrop as the dissolve has it, through derived state: reading the backdrop itself
    // recomposed the whole home screen on the first frame of every Top Shelf dissolve.
    val transition = LocalBackdropTransition.current
    val blank by remember(transition) { derivedStateOf { transition?.current == null } }
    val lightDock by remember(transition) { derivedStateOf { transition?.current?.dockLight == true } }
    val frost = rememberFrost()
    val controlFrost = rememberFrost(::controlShadeAt, clear = true)
    val quickFrost = rememberFrost(::quickShadeAt, clear = true)
    // Light glass on light art, dark on dark; eased so it changes with the dissolve.
    val dockLight = animateFloatAsState(if (lightDock) 1f else 0f, tween(480), label = "dockLight")

    Box(
        Modifier
            .fillMaxSize()
            .then(if (blank) Modifier.background(Color.Black) else Modifier)
            .graphicsLayer {
                // `returning` is set before the first frame back, so the zoom never flickers.
                val s = if (c.returning) RETURN_ZOOM else zoom.value
                scaleX = s
                scaleY = s
            }
            .onPreviewKeyEvent { c.onKey(it) }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        // The home screen itself; the inputs menu, the control center and the quick settings cover it with frost made from a copy of it.
        Box(Modifier.fillMaxSize().frostSource(frost).frostSource(controlFrost).frostSource(quickFrost)) {
            // Top Shelf changes dissolve into each other, the new one settling in with a slight zoom.
            // Once the live aerial has a picture, the still fades away and only the grid's glass is drawn over it.
            val video = animateFloatAsState(if (liveVideo) 1f else 0f, tween(if (liveVideo) 1200 else STILL_FADE_MS), label = "liveVideo")
            Wallpaper(transition, { grid.value }, { video.value })
            // Lower half darkened a little so the dock and labels stay legible. The pictures have it
            // baked in (backdropOf); only the live aerial needs it drawn, and a hidden layer costs nothing.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (1f - grid.value) * (1f - 0.65f * dockLight.value) * video.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawBehind {
                        val top = size.height * 0.55f
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = SHADE), startY = top, endY = size.height),
                            topLeft = Offset(0f, top),
                        )
                    },
            )

            if (bigClock) {
                ClockLayer(
                    glassClock,
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp)
                        .graphicsLayer {
                            translationY = -scroll.value.dp.toPx() * 0.85f
                            alpha = 1f - grid.value
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                )
            }

            StatusCapsule(
                c,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 30.dp, end = 40.dp)
                    .graphicsLayer {
                        translationY = -scroll.value.dp.toPx() * 0.6f
                        alpha = (1f - grid.value) * (1f - panelOpen.value)
                        // Each of its draws fades on its own: no offscreen buffer (see fade() in Overlays).
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            )

            // The dock: one slab of Liquid Glass behind the top row, rendered with its refraction for
            // each backdrop (DockGlass); it dissolves along with the Top Shelf behind it. Over the
            // grid's frosted glass it turns into a lighter glass shelf, as on the tvOS home screen.
            val dockShape = RoundedCornerShape(Grid.DOCK_RADIUS.dp)
            Box(
                Modifier
                    .offset { IntOffset(Grid.dock.left.dp.roundToPx(), (Grid.dock.top - scroll.value).dp.roundToPx()) }
                    .size(Grid.dock.width.dp, Grid.dock.height.dp)
                    .graphicsLayer {
                        shadowElevation = (18f - 8f * grid.value).dp.toPx()
                        shape = dockShape
                        clip = false
                        ambientShadowColor = Color.Black.copy(alpha = 0.22f)
                        spotShadowColor = Color.Black.copy(alpha = 0.32f)
                    }
                    .drawWithCache {
                        val r = CornerRadius(Grid.DOCK_RADIUS.dp.toPx())
                        // The grid's shelf: milky glass, light on its upper half, a bright rim at the top left.
                        val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.3f), 0.55f to Color.Transparent)
                        val rim = Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.95f),
                            0.3f to Color.White.copy(alpha = 0.35f),
                            0.7f to Color.White.copy(alpha = 0.12f),
                            1f to Color.White.copy(alpha = 0.55f),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        )
                        val rimWidth = 1.2.dp.toPx()
                        onDrawBehind {
                            val g = grid.value
                            val t = transition ?: return@onDrawBehind
                            val current = t.current ?: return@onDrawBehind
                            val previous = t.previous
                            val p = if (previous != null) t.progress.value else 1f
                            if (previous != null && p < 1f) drawFit(previous.dock, 1f - g)
                            drawFit(current.dock, p * (1f - g))
                            if (g > 0.002f) {
                                drawRoundRect(Color.White.copy(alpha = 0.3f), cornerRadius = r, alpha = g)
                                drawRoundRect(sheen, cornerRadius = r, alpha = g)
                                drawRoundRect(rim, cornerRadius = r, style = Stroke(rimWidth), alpha = g)
                            }
                        }
                    },
            )

            FocusLens({ c.focusedAppIndex().takeIf { it in 0 until Grid.COLS && c.movingKey == null } }, { scroll.value }, { dockLight.value })

            Box(Modifier.fillMaxSize().graphicsLayer { translationY = -scroll.value.dp.toPx() }) {
                // Context-menu dim lives in the tile layer so the held icon can stay above it. Drawn as a
                // fading colour, not a fading layer: a full-screen offscreen buffer was the menu's costliest frame.
                val dim = animateFloatAsState(if (c.menuKey != null) 1f else 0f, tween(if (c.menuKey != null) 180 else 200), label = "menuDim")
                Box(
                    Modifier
                        .zIndex(1f)
                        .offset { IntOffset(0, scroll.value.dp.roundToPx()) }
                        .fillMaxSize()
                        .drawBehind { if (dim.value > 0.002f) drawRect(Color.Black.copy(alpha = 0.38f * dim.value)) },
                )
                apps.forEachIndexed { i, app ->
                    key(app.key) { HomeTile(c, i, app, shadow, scroll, grid) }
                }
            }
        }

        MoveHint(c.movingKey != null)
        AppMenu(c)
        SettingsPanel(c)
        InputsMenu(c, frost)
        ControlCenterMenu(c, controlFrost)
        QuickSettingsFrost(c, quickFrost)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * One icon of the home screen, reading its own share of the focus through derived state: a move
 * recomposes the icon the focus leaves and the one it reaches, not every icon and the screen around
 * them (~5 ms of the TV's main thread on every press before).
 */
@Composable
private fun HomeTile(c: HomeController, i: Int, app: AppEntry, shadow: ImageBitmap, scroll: State<Float>, grid: State<Float>) {
    val focused by remember(c, i) { derivedStateOf { c.focusedAppIndex() == i } }
    val moving by remember(c, app.key) { derivedStateOf { c.movingKey == app.key } }
    val editing by remember(c) { derivedStateOf { c.movingKey != null } }
    AppTile(
        app = app,
        x = Grid.x(i),
        y = Grid.y(i),
        focused = focused,
        moving = moving,
        editing = editing,
        enter = if (focused) c.enter else DirEvent.None,
        bump = if (focused) c.bump else DirEvent.None,
        shadow = shadow,
        layer = if (focused) 2f else 0f,
        // In the dock the Top Shelf already says which app it is, so no label (tvOS 26).
        showLabel = i >= Grid.COLS || editing,
        focusScale = if (i < Grid.COLS) Grid.DOCK_FOCUS_SCALE else 0.17f,
        labelOnLight = { grid.value },
        // With room for the lift, the shadow and the label.
        onScreen = {
            val top = Grid.y(i) - scroll.value
            top < 540f + 30f && top + Grid.TILE_H + 45f > 0f
        },
    )
}

private const val RETURN_ZOOM = 1.07f

@Composable
private fun ClockLayer(glass: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val now by rememberMinute()
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        BigClock(formatTime(context, now), formatDate(strings, now), glass)
    }
}

@Composable
private fun Wallpaper(transition: BackdropTransition?, grid: () -> Float, video: () -> Float) {
    Canvas(Modifier.fillMaxSize()) {
        val t = transition ?: return@Canvas
        val current = t.current ?: return@Canvas
        val previous = t.previous
        val p = if (previous != null) t.progress.value else 1f
        val g = grid()
        val v = video()
        if (previous == null || p >= 1f) {
            drawBackdrop(current, g, 1f, v)
            return@Canvas
        }
        // Blur dissolve: the old art melts into the new one's blur, which sharpens into the new art
        // with a slight settle, so two logos are never seen on top of each other. Mostly two
        // full-screen layers, at most three: the TV's GPU is fill-rate bound.
        // Over the live aerial only the grid's glass dissolves: the video itself is the picture.
        val old = 1f - smoothstep(0f, 0.4f, p)
        val new = smoothstep(0.3f, 1f, p)
        if (v < 0.999f && old < 0.999f && new < 0.999f) drawCover(current.soft, alpha = 1f - v)
        drawBackdrop(previous, g, old, v)
        scale(1f + 0.03f * (1f - p)) { drawBackdrop(current, g, new, v) }
    }
}

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Below the top row the wallpaper turns into blurred, dimmed glass. Hidden layers are skipped: the TV's GPU is fill-rate bound. */
private fun DrawScope.drawBackdrop(b: Backdrop, grid: Float, alpha: Float, video: Float) {
    if (alpha <= 0.002f) return
    if (grid < 0.999f && video < 0.999f) drawCover(b.sharp, alpha * (1f - video))
    if (grid > 0.004f) drawCover(b.blurred, alpha * grid)
}

/**
 * A Liquid Glass lens behind the focused icon in the dock, concentric with the icon and the dock. It
 * glides to the next icon on a spring, the leading edge ahead of the trailing one, so it stretches
 * like a drop of glass as it moves (the iOS 26 tab bar selection), and settles back to its shape.
 */
@Composable
private fun FocusLens(index: () -> Int?, scroll: () -> Float, light: () -> Float) {
    // The focus is followed in an effect, not read in composition, so the lens never recomposes as it moves.
    val start = remember { Grid.lensRect(Snapshot.withoutReadObservation(index) ?: 0) }
    val left = remember { Animatable(start.left) }
    val right = remember { Animatable(start.right) }
    val present by remember { derivedStateOf { index() != null } }
    val shown = animateFloatAsState(if (present) 1f else 0f, tween(220), label = "lens")
    LaunchedEffect(Unit) {
        // When the focus leaves the dock mid-glide the lens finishes its glide as it fades, instead of
        // stopping there stretched between two icons.
        snapshotFlow(index).filterNotNull().collectLatest { i ->
            val target = Grid.lensRect(i)
            val forward = target.left > left.value
            coroutineScope {
                launch { left.animateTo(target.left, spring(dampingRatio = 0.78f, stiffness = if (forward) 190f else 520f)) }
                launch { right.animateTo(target.right, spring(dampingRatio = 0.78f, stiffness = if (forward) 520f else 190f)) }
            }
        }
    }
    val shape = RoundedCornerShape(Grid.LENS_RADIUS.dp)
    Box(
        Modifier
            .offset { IntOffset(left.value.dp.roundToPx(), (start.top - scroll()).dp.roundToPx()) }
            .layout { measurable, _ ->
                val w = (right.value - left.value).dp.roundToPx().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, start.height.dp.roundToPx()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                alpha = shown.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // Lifted a little off the dock, like a drop of glass resting on it.
            .shadow(6.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.24f))
            .liquidGlass(
                shape,
                lens = 0.18f,
                tintOf = { lerp(Color.White.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.07f), light()) },
            ),
    )
}

/** Draws a bitmap over the whole of this element. */
private fun DrawScope.drawFit(image: ImageBitmap, alpha: Float = 1f) {
    if (alpha <= 0.002f) return
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

private fun DrawScope.drawCover(image: ImageBitmap, alpha: Float = 1f) {
    val scale = max(size.width / image.width, size.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}
