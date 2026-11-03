package com.andrej.glasstv.screensaver

import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.ui.AppFonts
import com.andrej.glasstv.ui.DisplayFont
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.formatDate
import com.andrej.glasstv.ui.formatTime
import com.andrej.glasstv.ui.rememberMinute
import com.andrej.glasstv.ui.textOutline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the tvOS 26 screen saver puts its clock; it moves between shots to stay off the subject. */
enum class ClockLayout { Center, StackRight, StackLeft }

/** The "Clock on Screen Saver" setting: the big tvOS 26 numerals, a small clock in the corner, or none. */
enum class SaverClock {
    Big, Small, Off;

    companion object {
        fun of(pref: String) = when (pref) {
            "small" -> Small
            "off" -> Off
            else -> Big
        }
    }
}

/**
 * The tvOS 26 screen saver clock: huge rounded numerals in milky, translucent white, either on one
 * line across the middle or as two stacked lines at one side.
 *
 * The numerals are drawn from their merged outlines ([textOutline]).
 */
@Composable
fun TvClock(layout: ClockLayout, modifier: Modifier = Modifier.fillMaxSize(), scale: Float = 1f) {
    val context = LocalContext.current
    val family = remember { AppFonts.clock(context) }
    val resolver = LocalFontFamilyResolver.current
    val typeface = remember(family) { resolver.resolve(family, FontWeight.Medium).value as? Typeface }
    val now by rememberMinute()
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val hours = SimpleDateFormat(if (is24) "HH" else "h", Locale.getDefault()).format(Date(now))
    val minutes = SimpleDateFormat("mm", Locale.getDefault()).format(Date(now))
    // Rendered into its own layer once a minute: photos and video move underneath for free.
    Spacer(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.typeface = typeface
                    letterSpacing = -0.01f
                    fontFeatureSettings = "ss01, tnum"
                }
                val lines = ArrayList<Pair<Path, Float>>(2)
                var digit = 0f
                when (layout) {
                    ClockLayout.Center -> {
                        paint.textSize = (262 * BIG * scale).sp.toPx()
                        digit = paint.textSize * DIGIT_EM
                        val (path, width) = textOutline(paint, "$hours:$minutes")
                        val baseline = size.height * 0.47f + digit / 2f
                        path.translate(Offset((size.width - width) / 2f, baseline))
                        lines += path to baseline - digit
                    }
                    ClockLayout.StackRight, ClockLayout.StackLeft -> {
                        // Two lines take up about 75% of the height, centred a little high, so the minutes
                        // end well above the location caption in the bottom corner. SF Pro Rounded's flat
                        // tops and bottoms (2, 7) need a clear gap between the lines.
                        paint.textSize = (264 * BIG * scale).sp.toPx()
                        digit = paint.textSize * DIGIT_EM
                        val (top, topWidth) = textOutline(paint, hours)
                        val (bottom, bottomWidth) = textOutline(paint, minutes)
                        val gap = digit * 0.18f
                        val margin = 52.dp.toPx() * scale
                        val blockTop = size.height * 0.46f - (digit * 2 + gap) / 2f
                        val width = maxOf(topWidth, bottomWidth)
                        val x0 = if (layout == ClockLayout.StackRight) size.width - margin - width else margin
                        // Lines share one edge, like the tvOS layout: right-aligned on the right, left on the left.
                        val right = layout == ClockLayout.StackRight
                        top.translate(Offset(if (right) x0 + width - topWidth else x0, blockTop + digit))
                        bottom.translate(Offset(if (right) x0 + width - bottomWidth else x0, blockTop + digit * 2 + gap))
                        lines += top to blockTop
                        lines += bottom to blockTop + digit + gap
                    }
                }
                val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(5, 0, 0, 0)
                    setShadowLayer(34.dp.toPx(), 0f, 4.dp.toPx(), android.graphics.Color.argb(41, 0, 0, 0))
                }
                val rim = 1.2.dp.toPx()
                onDrawBehind {
                    for ((path, top) in lines) glassDigits(path, top, digit, shadow, rim)
                }
            },
    )
}

/** The big clock, faded without a layer of its own on top of the one it already has. */
@Composable
internal fun FadingClock(layout: ClockLayout, alpha: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        TvClock(layout)
    }
}

/**
 * The big clock moving to [layout] with each shot: a dissolve between two places at most, however
 * fast Left/Right change the shots (a Crossfade stacked one full-screen clock per step).
 */
@Composable
fun MovingClock(layout: ClockLayout, durationMs: Int) {
    var from by remember { mutableStateOf<ClockLayout?>(null) }
    var to by remember { mutableStateOf(layout) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(layout) {
        if (layout == to) return@LaunchedEffect
        // Moving again mid-dissolve: whichever place is more on screen is the one it leaves.
        from = if (fade.value < 0.5f && from != null) from else to
        to = layout
        fade.snapTo(0f)
        fade.animateTo(1f, tween(durationMs))
        from = null
    }
    val leaving = from
    if (leaving != null && leaving != to) FadingClock(leaving) { 1f - fade.value }
    FadingClock(to) { if (leaving == null || leaving == to) 1f else fade.value }
}

/** The big clock at three quarters of the tvOS 26 size: still the subject, less of the picture under it. */
private const val BIG = 0.75f

/** Height of a lining digit (about 0.705 em in Nunito, 0.71 in SF Pro Rounded). */
private const val DIGIT_EM = 0.705f

/** Milky glass numerals: translucent white body, a gloss on the upper half and a thin bright rim. */
private fun DrawScope.glassDigits(path: Path, top: Float, digit: Float, shadow: android.graphics.Paint, rim: Float) {
    val bottom = top + digit
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), shadow) }
    drawPath(path, Color.White.copy(alpha = 0.72f))
    drawPath(path, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 1f to Color.Transparent, startY = top, endY = top + digit * 0.55f))
    drawPath(
        path,
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.75f), 0.6f to Color.White.copy(alpha = 0.15f), 1f to Color.White.copy(alpha = 0.35f), startY = top, endY = bottom),
        style = Stroke(rim, join = StrokeJoin.Round),
    )
}

/**
 * The small screen saver clock: the time and date in a top corner, in plain white with a soft
 * shadow, so the picture is the subject and the time is still there at a glance. With each [change]
 * (a new shot or photo) it moves to the other corner, left and right in turn, so it never sits over
 * one part of the picture, or one part of the panel, for long.
 */
@Composable
fun SmallClock(change: Int) {
    val right = change % 2 != 0
    val side = animateFloatAsState(if (right) 1f else 0f, tween(1200), label = "smallClockSide")
    Box(Modifier.fillMaxSize()) {
        CornerClock(Alignment.TopStart, { 1f - side.value })
        CornerClock(Alignment.TopEnd, { side.value })
    }
}

/** For savers without shots of their own: the small clock changes corner every few minutes. */
@Composable
fun SmallClockOnTimer() {
    val now by rememberMinute()
    SmallClock((now / 180_000L).toInt())
}

@Composable
private fun BoxScope.CornerClock(corner: Alignment, alpha: () -> Float) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val now by rememberMinute()
    // A wide, soft halo: white on a bright sky or snow was barely there with a light shadow.
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 30f)
    val end = corner == Alignment.TopEnd
    Column(
        Modifier
            .align(corner)
            .padding(start = 52.dp, end = 52.dp, top = 40.dp)
            .graphicsLayer {
                this.alpha = alpha()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
    ) {
        BasicText(
            formatTime(context, now),
            style = TextStyle(
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 46.sp,
                letterSpacing = (-0.5).sp,
                fontFeatureSettings = "tnum",
                color = Color.White.copy(alpha = 0.92f),
                shadow = shadow,
            ),
        )
        BasicText(
            formatDate(strings, now),
            Modifier.padding(horizontal = 2.dp),
            style = TextStyle(
                fontFamily = TextFont,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.8f),
                shadow = shadow,
            ),
        )
    }
}
