package com.andrej.glasstv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small SF Symbols-like line glyphs, drawn on a 24x24 grid. */
enum class Glyph {
    Screensaver, Television, Sliders, Gear, Move, Hide, Info, Trash, Eye, Hdmi, Antenna, Rca, ChevronDown,
    Power, Toggles, Speaker, SpeakerOff, Bluetooth, Lightbulb, LightbulbOn, Back, Checkmark, HifiSpeaker,
}

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier, color: () -> Color) {
    Canvas(modifier) { drawGlyph(glyph, color()) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val k = size.minDimension / 24f
    val stroke = Stroke(width = 1.9f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    when (glyph) {
        // SF Symbols "photo": a landscape in a frame, for the screen savers' aerials and photos.
        Glyph.Screensaver -> {
            drawRoundRect(color, p(2.5f, 4.5f), Size(19f * k, 15f * k), CornerRadius(3f * k), style = stroke)
            val hills = Path().apply {
                moveTo(5.6f * k, 16.6f * k); lineTo(10f * k, 11.2f * k); lineTo(13.4f * k, 15f * k)
                lineTo(15.4f * k, 12.8f * k); lineTo(18.4f * k, 16.6f * k); close()
            }
            drawPath(hills, color, style = Stroke(1.2f * k, join = StrokeJoin.Round))
            drawPath(hills, color)
            drawCircle(color, 1.8f * k, p(16f, 8.6f))
        }
        Glyph.Television -> {
            drawRoundRect(color, p(2.5f, 4f), Size(19f * k, 13f * k), androidx.compose.ui.geometry.CornerRadius(2.5f * k), style = stroke)
            line(8f, 20.5f, 16f, 20.5f)
        }
        Glyph.Sliders -> {
            line(3.5f, 7f, 20.5f, 7f); line(3.5f, 12f, 20.5f, 12f); line(3.5f, 17f, 20.5f, 17f)
            drawCircle(color, 2.6f * k, p(9f, 7f)); drawCircle(color, 2.6f * k, p(16f, 12f)); drawCircle(color, 2.6f * k, p(7f, 17f))
        }
        Glyph.Gear -> drawPath(gear(12f * k, 12f * k, 10f * k), color)
        Glyph.Move -> {
            line(12f, 3f, 12f, 21f); line(3f, 12f, 21f, 12f)
            line(9f, 6f, 12f, 3f); line(15f, 6f, 12f, 3f)
            line(9f, 18f, 12f, 21f); line(15f, 18f, 12f, 21f)
            line(6f, 9f, 3f, 12f); line(6f, 15f, 3f, 12f)
            line(18f, 9f, 21f, 12f); line(18f, 15f, 21f, 12f)
        }
        Glyph.Eye, Glyph.Hide -> {
            val eye = Path().apply {
                moveTo(2f * k, 12f * k)
                quadraticTo(12f * k, 3f * k, 22f * k, 12f * k)
                quadraticTo(12f * k, 21f * k, 2f * k, 12f * k)
                close()
            }
            drawPath(eye, color, style = stroke)
            drawCircle(color, 3f * k, p(12f, 12f), style = stroke)
            if (glyph == Glyph.Hide) line(4f, 20f, 20f, 4f)
        }
        Glyph.Info -> {
            drawCircle(color, 9.5f * k, p(12f, 12f), style = stroke)
            line(12f, 11f, 12f, 17f)
            drawCircle(color, 1.3f * k, p(12f, 7.5f))
        }
        Glyph.Trash -> {
            line(4f, 6.5f, 20f, 6.5f)
            val lid = Path().apply { moveTo(9f * k, 6.5f * k); lineTo(9.5f * k, 3.8f * k); lineTo(14.5f * k, 3.8f * k); lineTo(15f * k, 6.5f * k) }
            drawPath(lid, color, style = stroke)
            val body = Path().apply { moveTo(6f * k, 6.5f * k); lineTo(7f * k, 20.5f * k); lineTo(17f * k, 20.5f * k); lineTo(18f * k, 6.5f * k) }
            drawPath(body, color, style = stroke)
            line(10f, 10f, 10.3f, 17f); line(14f, 10f, 13.7f, 17f)
        }
        Glyph.Hdmi -> {
            // The port, wide with its lower corners cut, and the contacts inside.
            val port = Path().apply {
                moveTo(2.5f * k, 8f * k); lineTo(21.5f * k, 8f * k); lineTo(21.5f * k, 12.5f * k)
                lineTo(18.5f * k, 16f * k); lineTo(5.5f * k, 16f * k); lineTo(2.5f * k, 12.5f * k)
                close()
            }
            drawPath(port, color, style = stroke)
            line(7f, 11.5f, 17f, 11.5f)
        }
        Glyph.Antenna -> {
            drawRoundRect(color, p(3f, 10f), Size(18f * k, 10.5f * k), androidx.compose.ui.geometry.CornerRadius(2.2f * k), style = stroke)
            line(12f, 10f, 7.5f, 3.5f); line(12f, 10f, 16.5f, 3.5f)
        }
        Glyph.Rca -> for (x in listOf(5.5f, 12f, 18.5f)) drawCircle(color, 2.7f * k, p(x, 12f), style = stroke)
        Glyph.ChevronDown -> {
            val chevron = Path().apply { moveTo(5.5f * k, 9f * k); lineTo(12f * k, 15.5f * k); lineTo(18.5f * k, 9f * k) }
            drawPath(chevron, color, style = Stroke(width = 2.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // SF Symbols "power": a ring open at the top, the bar through the gap.
        Glyph.Power -> {
            val ring = Stroke(width = 2.2f * k, cap = StrokeCap.Round)
            drawArc(color, -55f, 290f, false, p(3f, 4f), Size(18f * k, 18f * k), style = ring)
            drawLine(color, p(12f, 2.5f), p(12f, 11.5f), strokeWidth = 2.2f * k, cap = StrokeCap.Round)
        }
        // "switch.2", the Control Center symbol: a switch that is off above one that is on.
        Glyph.Toggles -> {
            drawRoundRect(color, p(3.5f, 3.5f), Size(17f * k, 7.6f * k), CornerRadius(3.8f * k), style = Stroke(1.7f * k))
            drawCircle(color, 2.2f * k, p(7.6f, 7.3f))
            val on = Path().apply {
                fillType = PathFillType.EvenOdd
                addRoundRect(RoundRect(3.5f * k, 12.9f * k, 20.5f * k, 20.5f * k, CornerRadius(3.8f * k)))
                addOval(Rect(p(16.4f, 16.7f), 2.2f * k))
            }
            drawPath(on, color)
        }
        Glyph.Speaker, Glyph.SpeakerOff -> {
            val body = Path().apply {
                moveTo(3f * k, 9.2f * k); lineTo(6.8f * k, 9.2f * k); lineTo(11.5f * k, 5f * k)
                lineTo(11.5f * k, 19f * k); lineTo(6.8f * k, 14.8f * k); lineTo(3f * k, 14.8f * k); close()
            }
            drawPath(body, color, style = Stroke(1.4f * k, join = StrokeJoin.Round))
            drawPath(body, color)
            if (glyph == Glyph.SpeakerOff) {
                line(15f, 9f, 21f, 15f); line(21f, 9f, 15f, 15f)
            } else {
                for (r in listOf(4.2f, 8f)) {
                    drawArc(color, -48f, 96f, false, p(11.5f - r, 12f - r), Size(2 * r * k, 2 * r * k), style = Stroke(1.9f * k, cap = StrokeCap.Round))
                }
            }
        }
        Glyph.Bluetooth -> {
            val rune = Path().apply {
                moveTo(6.8f * k, 7.6f * k); lineTo(17f * k, 16.4f * k); lineTo(12f * k, 21f * k)
                lineTo(12f * k, 3f * k); lineTo(17f * k, 7.6f * k); lineTo(6.8f * k, 16.4f * k)
            }
            drawPath(rune, color, style = stroke)
        }
        // "lightbulb" and, lit, "lightbulb.fill": the ambient light behind the TV.
        Glyph.Lightbulb, Glyph.LightbulbOn -> {
            val bulb = Path().apply {
                // The glass, round at the top and narrowing into its neck.
                moveTo(9.1f * k, 15.4f * k)
                cubicTo(7f * k, 14.1f * k, 5.6f * k, 11.9f * k, 5.6f * k, 9.5f * k)
                cubicTo(5.6f * k, 5.9f * k, 8.5f * k, 3f * k, 12f * k, 3f * k)
                cubicTo(15.5f * k, 3f * k, 18.4f * k, 5.9f * k, 18.4f * k, 9.5f * k)
                cubicTo(18.4f * k, 11.9f * k, 17f * k, 14.1f * k, 14.9f * k, 15.4f * k)
                close()
            }
            drawPath(bulb, color, style = stroke)
            if (glyph == Glyph.LightbulbOn) drawPath(bulb, color)
            line(9.4f, 18.3f, 14.6f, 18.3f)
            line(10.3f, 21f, 13.7f, 21f)
        }
        // "arrow.uturn.backward": back to the app before.
        Glyph.Back -> {
            val arrow = Path().apply {
                moveTo(9f * k, 5f * k); lineTo(4.5f * k, 9.5f * k); lineTo(9f * k, 14f * k)
                moveTo(4.5f * k, 9.5f * k); lineTo(14f * k, 9.5f * k)
                quadraticTo(19.5f * k, 9.5f * k, 19.5f * k, 14.5f * k)
                quadraticTo(19.5f * k, 19.5f * k, 14f * k, 19.5f * k)
                lineTo(10f * k, 19.5f * k)
            }
            drawPath(arrow, color, style = Stroke(2.1f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // "checkmark": the choice that is on, in a list.
        Glyph.Checkmark -> {
            val tick = Path().apply { moveTo(4.5f * k, 12.8f * k); lineTo(9.6f * k, 18f * k); lineTo(19.5f * k, 6.2f * k) }
            drawPath(tick, color, style = Stroke(2.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // "hifispeaker": a speaker cabinet, tweeter above woofer, for where the sound goes.
        Glyph.HifiSpeaker -> {
            drawRoundRect(color, p(5.5f, 2.5f), Size(13f * k, 19f * k), CornerRadius(2.8f * k), style = stroke)
            drawCircle(color, 1.5f * k, p(12f, 7f))
            drawCircle(color, 3.4f * k, p(12f, 14.8f), style = Stroke(1.7f * k))
        }
    }
}

/** SF Symbols "gearshape.fill": eight rounded teeth on a ring with a hole. */
private fun gear(cx: Float, cy: Float, r: Float) = Path().apply {
    fillType = PathFillType.EvenOdd
    val body = r * 0.76f
    val teeth = 8
    for (i in 0 until teeth) {
        val a = i * 2 * PI / teeth
        val half = PI / teeth
        fun pt(angle: Double, radius: Float) = Offset(cx + (cos(angle) * radius).toFloat(), cy + (sin(angle) * radius).toFloat())
        val p0 = pt(a - half * 0.62, body)
        val p1 = pt(a - half * 0.40, r)
        val p2 = pt(a + half * 0.40, r)
        val p3 = pt(a + half * 0.62, body)
        val next = pt(a + half * 1.38, body)
        if (i == 0) moveTo(p0.x, p0.y) else lineTo(p0.x, p0.y)
        lineTo(p1.x, p1.y)
        quadraticTo(pt(a, r * 1.04f).x, pt(a, r * 1.04f).y, p2.x, p2.y)
        lineTo(p3.x, p3.y)
        quadraticTo(pt(a + half, body * 1.02f).x, pt(a + half, body * 1.02f).y, next.x, next.y)
    }
    close()
    addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r * 0.34f))
}

/**
 * SF Symbols "wifi": a dot and three arcs, the ones beyond [bars] dimmed like the iOS status bar.
 * Offline, everything is dimmed and struck through ("wifi.slash").
 */
fun DrawScope.drawWifi(online: Boolean, bars: Int, color: Color) {
    val k = size.minDimension / 24f
    val center = Offset(12f * k, 19.5f * k)
    val dim = color.copy(alpha = color.alpha * 0.32f)
    fun lit(level: Int) = if (online && bars >= level) color else dim
    drawCircle(if (online) color else dim, 1.9f * k, center)
    for ((level, r) in listOf(1 to 5.6f, 2 to 10.1f, 3 to 14.6f)) {
        drawArc(
            lit(level),
            startAngle = -135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - r * k, center.y - r * k),
            size = Size(2 * r * k, 2 * r * k),
            style = Stroke(width = 2.5f * k, cap = StrokeCap.Round),
        )
    }
    if (!online) drawLine(color, Offset(4f * k, 3.5f * k), Offset(20f * k, 21.5f * k), strokeWidth = 2.2f * k, cap = StrokeCap.Round)
}

/** An Ethernet port, for a TV on a cable. */
fun DrawScope.drawEthernet(color: Color) {
    val k = size.minDimension / 24f
    val port = Path().apply {
        moveTo(3.5f * k, 8.5f * k)
        lineTo(7.5f * k, 8.5f * k); lineTo(7.5f * k, 5f * k); lineTo(16.5f * k, 5f * k); lineTo(16.5f * k, 8.5f * k)
        lineTo(20.5f * k, 8.5f * k); lineTo(20.5f * k, 19.5f * k); lineTo(3.5f * k, 19.5f * k)
        close()
    }
    drawPath(port, color, style = Stroke(width = 1.9f * k, join = StrokeJoin.Round))
    for (x in listOf(9f, 12f, 15f)) {
        drawLine(color, Offset(x * k, 15f * k), Offset(x * k, 17.2f * k), strokeWidth = 1.6f * k, cap = StrokeCap.Round)
    }
}
