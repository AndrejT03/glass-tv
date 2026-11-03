package com.andrej.glasstv.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.damageBounds
import com.andrej.glasstv.ui.glass.boxBlur
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val LabelStyle = TextStyle(
    fontFamily = TextFont,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    color = Color.White,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1f), 6f),
)

/** Labels over the grid's light grey: dark, without the shadow they need over art. */
private val LabelOnLightStyle = LabelStyle.copy(shadow = null)
private val LabelDark = Color(0xFF2C2C2E)

private const val SHADOW_TILE_W = 75
private const val SHADOW_TILE_H = 45
private const val SHADOW_PAD = 22

/** A soft rounded-rect shadow, blurred once on the CPU and stretched under every tile. */
fun makeTileShadow(): ImageBitmap {
    val w = SHADOW_TILE_W + SHADOW_PAD * 2
    val h = SHADOW_TILE_H + SHADOW_PAD * 2
    val lum = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(lum).apply {
        drawColor(android.graphics.Color.BLACK)
        val r = RectF(SHADOW_PAD.toFloat(), SHADOW_PAD.toFloat(), (SHADOW_PAD + SHADOW_TILE_W).toFloat(), (SHADOW_PAD + SHADOW_TILE_H).toFloat())
        drawRoundRect(r, 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    }
    val blurred = boxBlur(lum, radius = 6)
    val px = IntArray(w * h).also { blurred.getPixels(it, 0, w, 0, 0, w, h) }
    for (i in px.indices) px[i] = (px[i] and 0xFF) shl 24 // luminance becomes alpha, colour black
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * One tvOS app icon: lifts and grows on focus with a parallax tilt toward the direction of travel,
 * a moving glare and a glass rim; nudges against edges; wiggles while the home screen is edited.
 */
@Composable
fun AppTile(
    app: AppEntry,
    x: Float,
    y: Float,
    focused: Boolean,
    moving: Boolean,
    editing: Boolean,
    enter: DirEvent,
    bump: DirEvent,
    shadow: ImageBitmap,
    layer: Float,
    showLabel: Boolean,
    /** How much a focused icon grows: less in the dock, where the glass lens frames it. */
    focusScale: Float = 0.17f,
    /** 0 over the Top Shelf art, 1 over the grid's light grey: the label turns dark. */
    labelOnLight: () -> Float = { 0f },
    /** False while the icon is scrolled off the screen: it is then not drawn at all. */
    onScreen: () -> Boolean = { true },
) {
    val ax = remember { Animatable(x) }
    val ay = remember { Animatable(y) }
    LaunchedEffect(x, y) {
        launch { ax.animateTo(x, Motion.Reorder) }
        launch { ay.animateTo(y, Motion.Reorder) }
    }
    val focus = animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "focus")
    val lift = animateFloatAsState(if (moving) 1f else 0f, Motion.Focus, label = "lift")
    val tilt = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val nudge = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    // An icon the focus leaves settles back flat. Its tilt and nudge used to stop where they were
    // when the next press came first, so icons passed over stayed a little turned (and, turned,
    // kept drawing through an offscreen layer).
    LaunchedEffect(enter) {
        if (enter.dir == Dir.None) {
            if (tilt.value != Offset.Zero) tilt.animateTo(Offset.Zero, Motion.Tilt)
            return@LaunchedEffect
        }
        tilt.snapTo(Offset(enter.dir.x, enter.dir.y) * 7f)
        tilt.animateTo(Offset.Zero, Motion.Tilt)
    }
    LaunchedEffect(bump) {
        if (bump.dir == Dir.None) {
            if (nudge.value != Offset.Zero) nudge.animateTo(Offset.Zero, spring(dampingRatio = 0.4f, stiffness = 520f))
            return@LaunchedEffect
        }
        val d = Offset(bump.dir.x, bump.dir.y)
        coroutineScope {
            launch {
                nudge.animateTo(d * 7f, tween(90, easing = FastOutSlowInEasing))
                nudge.animateTo(Offset.Zero, spring(dampingRatio = 0.4f, stiffness = 520f))
            }
            launch {
                tilt.animateTo(d * 6f, tween(90, easing = FastOutSlowInEasing))
                tilt.animateTo(Offset.Zero, Motion.Tilt)
            }
        }
    }
    val wiggle: State<Float>? = if (editing && !moving) {
        rememberInfiniteTransition(label = "wiggle")
            .animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(300, easing = LinearEasing)), label = "w")
    } else {
        null
    }
    val phase = remember(app.key) { (app.key.hashCode() and 0xFFFF) / 1000f }

    // A lit icon (focused or lifted) has its shadow, glare and the rest of its rim in layers of their
    // own, which only fade and move with the focus: nothing of the icon is drawn again on each frame
    // of its animation, so (with damageBounds) a move redraws the screen around the icon, not all of it.
    val lit by remember { derivedStateOf { focus.value > 0.01f || lift.value > 0.01f } }

    Box(
        Modifier
            .zIndex(layer)
            .offset { IntOffset(ax.value.dp.roundToPx(), ay.value.dp.roundToPx()) }
            .size(Grid.TILE_W.dp, Grid.TILE_H.dp)
            // Room for the icon grown, lifted, nudged and turned, and for its shadow under it.
            .damageBounds(84.dp, 80.dp, 84.dp, 112.dp)
            .graphicsLayer {
                // A hidden layer is skipped entirely: icons scrolled off the screen cost nothing.
                alpha = if (onScreen()) 1f else 0f
                val s = 1f + focusScale * focus.value + 0.08f * lift.value
                scaleX = s
                scaleY = s
                translationX = nudge.value.x.dp.toPx()
                translationY = nudge.value.y.dp.toPx() - 4.dp.toPx() * lift.value
                rotationY = tilt.value.x
                rotationX = -tilt.value.y
                rotationZ = wiggle?.let { sin(it.value + phase) * 1.3f } ?: 0f
                cameraDistance = 18f * density
            },
    ) {
        // Only a lifted icon casts the deep tvOS shadow; skipping it on resting icons removes most of
        // the grid's overdraw.
        if (lit) TileShadow(shadow, { focus.value }, { lift.value })
        // The icon's rounded rects are drawn in a layer of their own while it tilts. The GPU draws
        // a rounded rect analytically only without perspective; under the tilt Skia rasterized
        // each one on the CPU and uploaded it as a texture, on every frame. In the layer they are
        // plain 2D, and the tilt turns a finished texture.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = if (tilt.value != Offset.Zero) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithCache {
                    val radius = CornerRadius(Grid.TILE_RADIUS.dp.toPx())
                    val art = ImageShader(app.art).apply {
                        setLocalMatrix(Matrix().apply { setScale(size.width / app.art.width, size.height / app.art.height) })
                    }
                    val artBrush = ShaderBrush(art)
                    val rim = Rim(size, radius.x, 1.2.dp.toPx())
                    onDrawBehind {
                        drawRoundRect(artBrush, cornerRadius = radius)
                        // A lit icon's rim is TileRim's.
                        if (!lit) rim.draw(this, alpha = 0.2f)
                    }
                },
        ) {
            if (lit) {
                TileGlare({ focus.value }, { tilt.value })
                TileRim { focus.value }
            }
        }
    }
    // The colour follows the background every frame without recomposing; the shadow switches once.
    val onLight by remember { derivedStateOf { labelOnLight() > 0.5f } }
    if (showLabel) BasicText(
        app.label,
        Modifier
            .zIndex(layer)
            .offset { IntOffset((ax.value - 30f).dp.roundToPx(), (ay.value + Grid.TILE_H + 13f).dp.roundToPx()) }
            .width((Grid.TILE_W + 60f).dp)
            // Room for the label's rise and its soft shadow.
            .damageBounds(12.dp, 20.dp, 12.dp, 12.dp)
            .graphicsLayer {
                val f = maxOf(focus.value, lift.value).coerceIn(0f, 1f)
                alpha = if (onScreen()) f else 0f
                // A single run of text: fading each draw needs no offscreen buffer (see fade() in Overlays).
                compositingStrategy = CompositingStrategy.ModulateAlpha
                translationY = (1f - f) * -6.dp.toPx()
            },
        style = if (onLight) LabelOnLightStyle else LabelStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = { lerp(Color.White, LabelDark, labelOnLight()) },
    )
}

/** A lit icon's shadow: one image, faded and dropped as a layer as the icon lifts. */
@Composable
private fun BoxScope.TileShadow(shadow: ImageBitmap, focus: () -> Float, lift: () -> Float) {
    Spacer(
        Modifier
            .matchParentSize()
            .graphicsLayer {
                val f = focus().coerceIn(0f, 1.2f)
                val l = lift()
                alpha = (0.62f * f + 0.45f * l).coerceIn(0f, 0.9f)
                translationY = (4f + 12f * f + 8f * l).dp.toPx()
                // Each draw fades on its own: an offscreen buffer would also cut the shadow at the icon's edges.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawWithCache {
                val k = size.width / SHADOW_TILE_W
                val pad = (SHADOW_PAD * k).roundToInt()
                val shadowSize = IntSize((shadow.width * k).roundToInt(), (shadow.height * k).roundToInt())
                onDrawBehind {
                    drawImage(
                        shadow,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(shadow.width, shadow.height),
                        dstOffset = IntOffset(-pad, -pad),
                        dstSize = shadowSize,
                        filterQuality = FilterQuality.Low,
                    )
                }
            },
    )
}

/**
 * A lit icon's glare, following the tilt: a fixed gradient that a layer fades and moves under a clip
 * to the icon's shape.
 */
@Composable
private fun BoxScope.TileGlare(focus: () -> Float, tilt: () -> Offset) {
    Box(Modifier.matchParentSize().clip(RoundedCornerShape(Grid.TILE_RADIUS.dp))) {
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val t = tilt()
                    alpha = (0.24f * focus().coerceIn(0f, 1.2f)).coerceAtMost(0.3f)
                    translationX = size.width * t.x / 22f
                    translationY = size.height * t.y / 16f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawWithCache {
                    val glare = Brush.radialGradient(
                        listOf(Color.White, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * -0.3f),
                        radius = size.width * 0.9f,
                    )
                    // Well past the icon on every side, so the clip stays covered wherever the glare has moved.
                    onDrawBehind { drawRect(glare, Offset(-size.width, -size.height), Size(size.width * 3f, size.height * 3f)) }
                },
        )
    }
}

/** A lit icon's rim, brightening as a layer as the focus comes. */
@Composable
private fun BoxScope.TileRim(focus: () -> Float) {
    Spacer(
        Modifier
            .matchParentSize()
            .graphicsLayer {
                alpha = (0.2f + 0.6f * focus().coerceIn(0f, 1.2f)).coerceAtMost(1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawWithCache {
                val rim = Rim(size, Grid.TILE_RADIUS.dp.toPx(), 1.2.dp.toPx())
                onDrawBehind { rim.draw(this) }
            },
    )
}

/** The glass rim just inside an icon's edge (so a layer's bounds never cut it), brightest at the top left. */
private class Rim(size: Size, radius: Float, private val width: Float) {
    private val brush = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.9f),
        0.35f to Color.White.copy(alpha = 0.16f),
        0.75f to Color.White.copy(alpha = 0.05f),
        1f to Color.White.copy(alpha = 0.4f),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    private val inset = Offset(width / 2f, width / 2f)
    private val rect = Size(size.width - width, size.height - width)
    private val corner = CornerRadius(radius - width / 2f)

    fun draw(scope: DrawScope, alpha: Float = 1f) = scope.drawRoundRect(brush, inset, rect, corner, style = Stroke(width), alpha = alpha)
}
