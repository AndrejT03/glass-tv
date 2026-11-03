package com.andrej.glasstv.inputs

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.data.InputKind
import com.andrej.glasstv.ui.Glyph
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.drawGlyph
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The list, in design units (960x540): in the bottom-left corner, where Messages has its "+" menu.
private const val LIST_LEFT = 52f
private const val LIST_W = 340f
private const val LIST_BOTTOM = 492f
private const val ROW = 48f
private const val PLATTER_H = 44f
private const val ICON = 32f

/** The icon sits concentric with the round end of the focus platter. */
private const val ICON_X = (PLATTER_H - ICON) / 2f

/** Room after the text on the platter, which hugs the row it is on. */
private const val ROW_END = 20f
private const val HEADER_H = 30f

// The shade hugs the bottom-left corner: solid behind the list (left of SHADE_RIGHT and below
// SHADE_TOP, in design units) and fading out over SHADE_FADE beyond that, round at its corner.
private const val SHADE_RIGHT = 400f
private const val SHADE_TOP = 230f
private const val SHADE_FADE = 290f

/** How deep the shade is (0..1) at (u, v), 0..1 across and down the screen. */
internal fun shadeAt(u: Float, v: Float): Float {
    val dx = (u * 960f - SHADE_RIGHT).coerceAtLeast(0f)
    val dy = (SHADE_TOP - v * 540f).coerceAtLeast(0f)
    val t = (sqrt(dx * dx + dy * dy) / SHADE_FADE).coerceIn(0f, 1f)
    // Smootherstep: no visible edge where the fade starts or ends.
    return 1f - t * t * t * (t * (t * 6f - 15f) + 10f)
}

/** The shade as a small black image, its depth in its transparency; the GPU smooths it when stretched. */
private val ShadeImage: ImageBitmap by lazy {
    val w = 240
    val h = 135
    val px = IntArray(w * h) { i -> (shadeAt((i % w + 0.5f) / w, (i / w + 0.5f) / h) * 255f).roundToInt() shl 24 }
    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** How far across the screen the shade reaches (0..1): nothing beyond it needs drawing. */
const val SHADE_REACH = (SHADE_RIGHT + SHADE_FADE) / 960f

/**
 * Draws the part of [image] (a picture of the whole screen) that the shade reaches, stretched over
 * the same part of a screen of [screenW] x [screenH] pixels: on the TV's GPU every pixel blended
 * costs, so the empty three tenths on the right are left out.
 */
fun DrawScope.drawCornerOf(image: ImageBitmap, alpha: Float, screenW: Float = size.width, screenH: Float = size.height) {
    if (alpha <= 0.002f) return
    val srcW = ceil(image.width * SHADE_REACH).toInt().coerceAtMost(image.width)
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(srcW, image.height),
        dstSize = IntSize((screenW * srcW / image.width).roundToInt(), screenH.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** The shade, black at [alpha] at its deepest: for where nothing can be blurred. */
fun DrawScope.drawCornerShade(alpha: Float, screenW: Float = size.width, screenH: Float = size.height) =
    drawCornerOf(ShadeImage, alpha, screenW, screenH)

/** Each row rises into place with a soft settle; the one nearest the corner first. */
private val Rise = spring<Float>(dampingRatio = 0.78f, stiffness = 360f)

/** The list sliding up as More unfolds the rest of the inputs above it. */
private val Unfold = spring<Float>(dampingRatio = 0.9f, stiffness = 280f)

/** Between one row starting to rise and the next. */
private const val STAGGER_MS = 26L

private val Ink = Color(0xFF111113)
private val PlatterShape = RoundedCornerShape(22.dp)

/**
 * The inputs menu's list: round icons with big white labels in the bottom-left corner, over a
 * shade (on the home screen, a dark frosted blur) that fades out toward the middle of the screen.
 * The rows rise into place one after another, the one nearest the corner first, each settling
 * softly, and the header last; they sink away together when it closes. More unfolds the inputs
 * with nothing plugged in, the list sliding up to make room as they rise in. The remote's focus is
 * a white platter that glides from row to row, the row on it in dark ink.
 */
@Composable
fun BoxScope.InputsList(model: InputsMenuModel, shown: Boolean) {
    val strings = LocalStrings.current
    val rows = model.rows()
    val n = rows.size
    // The rows there when the list came in: any others were unfolded by More.
    var first by remember { mutableStateOf(rows.map(::keyOf).toSet()) }
    LaunchedEffect(shown) {
        if (shown) first = rows.map(::keyOf).toSet()
    }

    // How wide each row is (design units), for the platter to hug the one in focus.
    val widths = remember { mutableStateMapOf<String, Float>() }
    val focused = model.index.coerceIn(0, (n - 1).coerceAtLeast(0))
    // The platter comes in under the row in focus, a moment after that row starts rising (from
    // nothing even when the list is shown from its first frame, as over other apps).
    val platterAnim = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(30L + (n - 1 - focused) * STAGGER_MS + 70L)
            platterAnim.animateTo(1f, tween(220, easing = Motion.EaseOut))
        } else {
            platterAnim.animateTo(0f, tween(110))
        }
    }
    val platter by platterAnim.asState()
    // It glides to the row in focus and takes its width; while it is out of sight it just jumps there.
    val focusY = remember { Animatable(rowTop(focused, n)) }
    val focusW = remember { Animatable(LIST_W) }
    val targetY = rowTop(focused, n)
    val targetW = rows.getOrNull(focused)?.let { widths[keyOf(it)] } ?: LIST_W
    LaunchedEffect(targetY) { if (platterAnim.value < 0.01f) focusY.snapTo(targetY) else focusY.animateTo(targetY, Motion.Highlight) }
    LaunchedEffect(targetW) { if (platterAnim.value < 0.01f) focusW.snapTo(targetW) else focusW.animateTo(targetW, Motion.Highlight) }
    Box(
        Modifier
            .offset { IntOffset(LIST_LEFT.dp.roundToPx(), (focusY.value + (ROW - PLATTER_H) / 2f).dp.roundToPx()) }
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(focusW.value.dp.roundToPx(), PLATTER_H.dp.roundToPx()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                alpha = platter
                val s = 0.94f + 0.06f * platter
                scaleX = s
                scaleY = s
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // No drop shadow: on the dark shade it would hardly show, and it costs the TV's GPU every frame.
            .background(Color.White.copy(alpha = 0.96f), PlatterShape),
    )

    // Bottom first: the rows nearest the corner rise first, then the ones above them.
    val unfoldedBelow = rows.indices.filter { keyOf(rows[it]) !in first }
    rows.forEachIndexed { i, row ->
        key(keyOf(row)) {
            val unfolded = keyOf(row) !in first
            val delayMs = if (unfolded) {
                (unfoldedBelow.size - 1 - unfoldedBelow.indexOf(i)) * STAGGER_MS
            } else {
                30L + (n - 1 - i) * STAGGER_MS
            }
            Rising(shown, delayMs, rowTop(i, n), Modifier.size(LIST_W.dp, ROW.dp)) {
                MenuRow(row, focused = i == model.index) { widths[keyOf(row)] = it }
            }
        }
    }
    // More fades out where it was as the inputs it unfolds rise in.
    val moreTop = remember { floatArrayOf(-1f) }
    val more = rows.indexOf(InputRow.More)
    if (more >= 0) {
        moreTop[0] = rowTop(more, n)
    } else if (moreTop[0] >= 0f) {
        key("more-leaving") { Leaving(moreTop[0]) { MenuRow(InputRow.More, focused = false) {} } }
    }
    Rising(shown, 30L + n * STAGGER_MS, rowTop(0, n) - HEADER_H, Modifier.size(LIST_W.dp, HEADER_H.dp)) {
        BasicText(strings.inputs.uppercase(strings.locale), Modifier.padding(start = ICON_X.dp), style = HeaderStyle)
    }
}

/** Rows hang from the bottom of the list, so More unfolding the rest moves the list up. */
private fun rowTop(i: Int, n: Int) = LIST_BOTTOM - (n - i) * ROW

private fun keyOf(row: InputRow) = when (row) {
    InputRow.More -> "more"
    is InputRow.Input -> row.input.id
    is InputRow.App -> "app/" + row.app.packageName
}

/** Where row [index] of [count] has its icon, in design units: an app opened from the menu zooms out of it. */
fun inputsIconRect(index: Int, count: Int): Rect {
    val top = rowTop(index, count) + (ROW - ICON) / 2f
    return Rect(LIST_LEFT + ICON_X, top, LIST_LEFT + ICON_X + ICON, top + ICON)
}

/**
 * Something in the list at [top], rising into place [delayMs] after the list starts coming in (or
 * after it first appears, unfolded by More) and sinking away when it goes.
 */
@Composable
private fun Rising(shown: Boolean, delayMs: Long, top: Float, modifier: Modifier, content: @Composable () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(delayMs)
            appear.animateTo(1f, Rise)
        } else {
            appear.animateTo(0f, tween(130, easing = FastOutLinearInEasing))
        }
    }
    val y by animateFloatAsState(top, Unfold, label = "inputRowY")
    Box(
        modifier
            .offset { IntOffset(LIST_LEFT.dp.roundToPx(), y.dp.roundToPx()) }
            .graphicsLayer {
                val p = appear.value
                translationY = (1f - p) * 22.dp.toPx()
                alpha = p.coerceIn(0f, 1f)
                val s = 0.92f + 0.08f * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin((ICON_X + ICON / 2f) / LIST_W, 0.5f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { content() }
}

/** A row that has just gone from the list, fading out and sinking a little where it was. */
@Composable
private fun Leaving(top: Float, content: @Composable () -> Unit) {
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) { fade.animateTo(0f, tween(170, easing = FastOutLinearInEasing)) }
    Box(
        Modifier
            .offset { IntOffset(LIST_LEFT.dp.roundToPx(), top.dp.roundToPx()) }
            .size(LIST_W.dp, ROW.dp)
            .graphicsLayer {
                alpha = fade.value
                translationY = (1f - fade.value) * 10.dp.toPx()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { content() }
}

/** A row: its icon and label centred on the row's height, its width reported for the platter. */
@Composable
private fun MenuRow(row: InputRow, focused: Boolean, onWidth: (Float) -> Unit) {
    val strings = LocalStrings.current
    val density = LocalDensity.current.density
    val focus by animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "inputRowFocus")
    val f = focus.coerceIn(0f, 1f)
    val dim = row is InputRow.Input && !row.input.connected
    val label = lerp(if (dim) Color.White.copy(alpha = 0.42f) else Color.White, if (dim) Ink.copy(alpha = 0.5f) else Ink, f)
    val port = lerp(Color.White.copy(alpha = 0.5f), Ink.copy(alpha = 0.45f), f)
    Row(
        Modifier
            .fillMaxHeight()
            .onSizeChanged { onWidth(it.width / density) }
            .padding(start = ICON_X.dp, end = ROW_END.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuIcon(row, dim)
        Spacer(Modifier.width(15.dp))
        when (row) {
            InputRow.More -> BasicText(strings.more, style = LabelStyle.copy(color = label))
            is InputRow.App -> BasicText(
                row.app.label,
                Modifier.weight(1f, fill = false),
                style = LabelStyle.copy(color = label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The port sits on the name's baseline, the two centred on the row together.
            is InputRow.Input -> Row(Modifier.weight(1f, fill = false)) {
                BasicText(
                    row.input.name,
                    Modifier.alignByBaseline().weight(1f, fill = false),
                    style = LabelStyle.copy(color = label),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.input.port?.let {
                    BasicText(it, Modifier.alignByBaseline().padding(start = 10.dp), style = PortStyle.copy(color = port), maxLines = 1)
                }
            }
        }
    }
}

/** A round glass icon: the socket's symbol in white on its colour, lit from above, with a fine rim. */
@Composable
private fun MenuIcon(row: InputRow, dim: Boolean) {
    if (row is InputRow.App) {
        AppIcon(row.app.packageName)
        return
    }
    val (tint, glyph) = when (row) {
        is InputRow.App -> return
        InputRow.More -> Color(0xFF636366) to Glyph.ChevronDown
        is InputRow.Input -> when (row.input.kind) {
            InputKind.Tuner -> Color(0xFFFF9500) to Glyph.Antenna
            InputKind.Hdmi -> Color(0xFF0A84FF) to Glyph.Hdmi
            InputKind.Composite -> Color(0xFF30D158) to Glyph.Rca
            InputKind.Other -> Color(0xFF8E8E93) to Glyph.Television
        }
    }
    val fill = if (dim) lerp(tint, Color(0xFF48484A), 0.7f) else tint
    Canvas(Modifier.size(ICON.dp)) {
        val r = size.minDimension / 2f
        drawCircle(Brush.verticalGradient(listOf(lerp(fill, Color.White, 0.22f), fill, lerp(fill, Color.Black, 0.12f))))
        // The light on the upper half of the glass.
        drawOval(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), startY = r * 0.1f, endY = r * 1.05f),
            topLeft = Offset(r * 0.28f, r * 0.1f),
            size = Size(r * 1.44f, r * 0.95f),
        )
        drawCircle(Color.White.copy(alpha = 0.22f), radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
        inset(size.minDimension * 0.24f) { drawGlyph(glyph, Color.White.copy(alpha = if (dim) 0.6f else 1f)) }
    }
}

/** A recently opened app: its own icon in a circle, with the same fine rim as the inputs' glass icons. */
@Composable
private fun AppIcon(pkg: String) {
    val density = LocalDensity.current.density
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = GlassApp.instance.recents.icon(pkg, (ICON * density).roundToInt())
    }
    Canvas(Modifier.size(ICON.dp)) {
        val r = size.minDimension / 2f
        val image = icon
        if (image == null) {
            drawCircle(Color.White.copy(alpha = 0.16f))
        } else {
            drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
        }
        drawCircle(Color.White.copy(alpha = 0.22f), radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

private val LabelStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 21.sp, color = Color.White)
private val PortStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
private val HeaderStyle = TextStyle(
    fontFamily = TextFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    letterSpacing = 1.6.sp,
    color = Color.White.copy(alpha = 0.6f),
)
