package com.andrej.glasstv.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.andrej.glasstv.ui.LocalScreenPx
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * tvOS 26 Liquid Glass material, without RuntimeShader:
 * - the body shows the frosted backdrop slightly magnified, like a lens;
 * - a band along the edge shows the backdrop pulled in from outside the shape — the refraction
 *   that makes Liquid Glass look like a thick piece of glass rather than a blurred card;
 * - a gloss on the upper half, a faint darkening toward the base;
 * - a specular rim, brightest at the top-left, with a secondary reflection at the bottom-right.
 *
 * @param highlight 0..1, how far the surface has turned into the bright "focused" glass.
 * @param sampleOffset extra translation (px) applied by a graphicsLayer, so sampling follows
 *   surfaces that move with one.
 * @param restsAt where the surface comes to rest (top left, from the screen's top left), for one that
 *   slides or scales in: it then shows the backdrop under that place throughout, so its glass stays
 *   the same while it moves and a layer holding it is not redrawn on every frame of the animation.
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = Color.White.copy(alpha = 0.06f),
    lens: Float = 0.1f,
    tintOf: (() -> Color)? = null,
    highlight: () -> Float = { 0f },
    sampleOffset: () -> Offset = { Offset.Zero },
    restsAt: DpOffset? = null,
): Modifier = composed {
    val transition = LocalBackdropTransition.current
    // With a transition (the home screen) the shaders follow it in the draw phase; reading the
    // backdrop here as well would recompose every glass surface whenever the Top Shelf changes.
    val fallback = if (transition == null) LocalBackdrop.current else null
    val screen = LocalScreenPx.current
    val origin = remember { mutableStateOf(Offset.Zero) }
    val band = remember { BandMask() }
    (if (restsAt == null) onGloballyPositioned { origin.value = it.positionInRoot() } else this)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            // The band never gets wider than the corners are round, or a big sheet would show it as a
            // square-cornered frame inside its rounded edge.
            val corner = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
            val edge = min(min(size.width, size.height) * 0.16f, corner * 1.1f)
            val bandPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            val rim = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.85f),
                0.22f to Color.White.copy(alpha = 0.22f),
                0.55f to Color.White.copy(alpha = 0.04f),
                0.85f to Color.White.copy(alpha = 0.18f),
                1f to Color.White.copy(alpha = 0.42f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            val shading = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.13f),
                0.45f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.07f),
            )
            val rimWidth = 1.1.dp.toPx()
            // Reading the transition here rebuilds these shaders whenever the backdrop changes.
            val backdrop = if (transition != null) transition.current else fallback
            val outgoing = transition?.previous
            val body = backdrop?.let { ImageShader(it.frost) }
            val refraction = backdrop?.let { ImageShader(it.frost) }
            val old = outgoing?.let { ImageShader(it.frost) }
            val bodyBrush = body?.let { ShaderBrush(it) }
            val oldBrush = old?.let { ShaderBrush(it) }
            val matrix = Matrix()
            onDrawBehind {
                if (backdrop != null && body != null && refraction != null && bodyBrush != null) {
                    val o = (restsAt?.let { Offset(it.x.toPx(), it.y.toPx()) } ?: origin.value) + sampleOffset()
                    val p = if (outgoing != null) transition?.progress?.value ?: 1f else 1f
                    if (outgoing != null && old != null && oldBrush != null && p < 1f) {
                        old.place(outgoing, screen, o, size, 1f + lens, matrix)
                        drawOutline(outline, oldBrush)
                    }
                    body.place(backdrop, screen, o, size, 1f + lens, matrix)
                    drawOutline(outline, bodyBrush, alpha = p)
                    // Edge band: the backdrop squeezed in from beyond the rim.
                    refraction.place(backdrop, screen, o, size, 0.72f, matrix)
                    if (edge > 0f) {
                        bandPaint.shader = refraction
                        clipPath(clip) { drawIntoCanvas { band.draw(it.nativeCanvas, size, corner, edge, bandPaint) } }
                    }
                }
                drawOutline(outline, lerp(tintOf?.invoke() ?: tint, Color.White.copy(alpha = 0.94f), highlight()))
                drawOutline(outline, shading)
                drawOutline(outline, rim, style = Stroke(rimWidth))
            }
        }
}

/**
 * How strongly the edge band shows the backdrop: most at the rim, easing to nothing [edge] inside
 * it, as one smooth gradient (stacked strokes showed as steps along the edge).
 *
 * Only the corners differ between surfaces, so the mask is one rounded square of the corner's size,
 * made once at low resolution (it is a soft gradient anyway) and drawn in nine slices: corners as
 * they are, the edges stretched along the sides. A surface that grows (the capsule opening) reuses it.
 */
private class BandMask {
    private var bitmap: Bitmap? = null
    private var made = Triple(0f, 0f, 0f)
    private val src = Rect()
    private val dst = RectF()

    fun draw(canvas: Canvas, size: Size, corner: Float, edge: Float, paint: Paint) {
        // Half the mask's side: wide enough for the corner's curve and the whole band.
        val half = min(max(corner, edge), min(size.width, size.height) / 2f)
        if (half <= 0f) return
        val scale = (BAND_PX / edge).coerceIn(0.2f, 1f)
        val key = Triple(half, corner, edge)
        val mask = bitmap?.takeIf { made == key } ?: make(half, min(corner, half), edge, scale).also {
            bitmap?.recycle()
            bitmap = it
            made = key
        }
        val m = mask.width
        val mh = m / 2
        val w = size.width
        val h = size.height
        fun piece(sl: Int, st: Int, sr: Int, sb: Int, dl: Float, dt: Float, dr: Float, db: Float) {
            if (dr <= dl || db <= dt) return
            src.set(sl, st, sr, sb)
            dst.set(dl, dt, dr, db)
            canvas.drawBitmap(mask, src, dst, paint)
        }
        piece(0, 0, mh, mh, 0f, 0f, half, half)
        piece(mh, 0, m, mh, w - half, 0f, w, half)
        piece(0, mh, mh, m, 0f, h - half, half, h)
        piece(mh, mh, m, m, w - half, h - half, w, h)
        // The sides: one column or row from the middle of the mask, stretched.
        piece(mh - 1, 0, mh, mh, half, 0f, w - half, half)
        piece(mh - 1, mh, mh, m, half, h - half, w - half, h)
        piece(0, mh - 1, mh, mh, 0f, half, half, h - half)
        piece(mh, mh - 1, m, mh, w - half, half, w, h - half)
    }

    private fun make(half: Float, corner: Float, edge: Float, scale: Float): Bitmap {
        val mh = ceil(half * scale).toInt().coerceAtLeast(2)
        val m = mh * 2
        val px = IntArray(m * m)
        val step = half / mh
        val inner = half - corner
        for (y in 0 until m) {
            val qy = abs((y + 0.5f) * step - half) - inner
            for (x in 0 until m) {
                val qx = abs((x + 0.5f) * step - half) - inner
                // Signed distance to the rounded square's edge, negative inside.
                val sd = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - corner
                val t = 1f - (-sd / edge).coerceIn(0f, 1f)
                px[y * m + x] = (BAND_ALPHA * t * t * 255f).toInt() shl 24
            }
        }
        return Bitmap.createBitmap(m, m, Bitmap.Config.ALPHA_8).apply { setPixels(px, 0, m, 0, 0, m, m) }
    }

    private companion object {
        /** Mask pixels across the band: enough for a smooth gradient once filtered. */
        const val BAND_PX = 16f

        /** The band's strength at the rim. */
        const val BAND_ALPHA = 0.6f
    }
}

/** Maps the frosted backdrop under a surface at [origin], scaled by [zoom] around the surface's center. */
private fun Shader.place(backdrop: Backdrop, screen: Size, origin: Offset, size: Size, zoom: Float, m: Matrix) {
    backdrop.coverMatrix(backdrop.frost, screen, m)
    m.postTranslate(-origin.x, -origin.y)
    m.postScale(zoom, zoom, size.width / 2f, size.height / 2f)
    setLocalMatrix(m)
}
