package com.andrej.glasstv.ui.glass

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.andrej.glasstv.ui.home.Grid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The tvOS 26 dock as one slab of Liquid Glass, rendered once per backdrop on the CPU.
 *
 * Android 12 has no RuntimeShader, so glass that really refracts can't be drawn live on the GPU. The
 * dock only ever sits over one backdrop at a time, though, so its glass is worked out when the
 * backdrop is made:
 * - the body shows what is behind it, softly blurred and a little more saturated;
 * - toward the rim the glass bends light like a rounded edge: each pixel shows the backdrop from
 *   further out, more so the closer it is to the edge, with a slight colour split (dispersion);
 * - the bevel catches light from above (a bright upper rim, a shaded base), and a thin specular line
 *   runs round the edge, brightest at the top and again, weaker, at the bottom.
 * Drawing it is then a single bitmap, cheaper than the layered shader glass it replaces.
 */
internal object DockGlass {
    /** Width of the curved rim, design units. */
    private const val BEVEL = 10f

    /** How far, in design units, the very edge reaches out for what it shows. */
    private const val REACH = 9f

    /** Colour split at the edge: blue reaches this much further than red. */
    private const val DISPERSION = 0.14f

    private const val SATURATION = 1.25f

    /** A distance from the edge past the bevel and the rim line: plain body. */
    private const val BODY = 1e6f

    /** Light from above, a little from the left (unit vector toward the light). */
    private const val LIGHT_X = -0.28f
    private const val LIGHT_Y = -0.96f

    /**
     * @param light the backdrop under the dock is bright: milky white glass instead of clear dark glass.
     * @return the dock at up to 2 px per design unit (1080p); bigger screens draw it scaled.
     */
    fun render(src: Bitmap, light: Boolean): Bitmap {
        val metrics = Resources.getSystem().displayMetrics
        val screenW = max(metrics.widthPixels, metrics.heightPixels).toFloat()
        val screenH = min(metrics.widthPixels, metrics.heightPixels).toFloat()
        val unit = min(screenW, 1920f) / 960f
        val virtualH = screenH * (960f * unit / screenW)

        val dock = Grid.dock
        val w = ceil(dock.width * unit).toInt()
        val h = ceil(dock.height * unit).toInt()
        val radius = Grid.DOCK_RADIUS * unit
        val bevel = BEVEL * unit
        val reach = REACH * unit
        val left = dock.left * unit
        val top = dock.top * unit

        // What the glass sees: the backdrop around the dock at half resolution, blurred.
        val margin = reach * (1 + DISPERSION) + 24f * unit
        val x0 = max(0f, left - margin)
        val y0 = max(0f, top - margin)
        val x1 = min(960f * unit, left + w + margin)
        val y1 = min(virtualH, top + h + margin)
        val bw = ceil((x1 - x0) / 2f).toInt()
        val bh = ceil((y1 - y0) / 2f).toInt()
        val view = behind(src, 960f * unit, virtualH, x0, y0, bw, bh)
        val px = IntArray(bw * bh).also { view.getPixels(it, 0, bw, 0, 0, bw, bh) }
        view.recycle()

        // The flat body: what the glass sees, tinted at half scale, then doubled by Skia.
        val rgb = FloatArray(3)
        val tinted = IntArray(px.size) { i ->
            val p = px[i]
            rgb[0] = (p shr 16 and 0xFF).toFloat()
            rgb[1] = (p shr 8 and 0xFF).toFloat()
            rgb[2] = (p and 0xFF).toFloat()
            shade(rgb, light, 0f, 0f, 0f, BODY, 1f)
        }
        val body = Bitmap.createBitmap(tinted, bw, bh, Bitmap.Config.ARGB_8888)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(body, Matrix().apply { setScale(2f, 2f); postTranslate(x0 - left, y0 - top) }, Paint(Paint.FILTER_BITMAP_FLAG))
        body.recycle()

        // The rim, pixel by pixel: refraction, dispersion, light and the dock's rounded outline.
        val hw = w / 2f
        val hh = h / 2f
        val band = ceil(bevel + 2f).toInt()
        val corner = max(band, ceil(radius).toInt())
        val row = IntArray(w)
        for (y in 0 until h) {
            val py = y + 0.5f - hh
            val qy = kotlin.math.abs(py) - (hh - radius)
            // Along the top and bottom every pixel is rim; beside the corners, the corner; elsewhere, the sides.
            val side = when {
                y < band || y >= h - band -> w
                y < corner || y >= h - corner -> corner
                else -> band
            }
            for (i in 0 until min(w, 2 * side)) {
                val x = if (i < side) i else w - 2 * side + i
                val pxl = x + 0.5f - hw
                val qx = kotlin.math.abs(pxl) - (hw - radius)
                // Signed distance to the rounded rectangle, and the outward normal of its nearest edge.
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val cornerDist = sqrt(ox * ox + oy * oy)
                val d = radius - cornerDist - min(max(qx, qy), 0f)
                if (d <= -0.5f) {
                    row[x] = 0
                    continue
                }
                val nx: Float
                val ny: Float
                if (qx > 0f && qy > 0f) {
                    nx = ox / cornerDist * sign(pxl)
                    ny = oy / cornerDist * sign(py)
                } else if (qx > qy) {
                    nx = sign(pxl); ny = 0f
                } else {
                    nx = 0f; ny = sign(py)
                }
                // 1 at the edge, 0 where the rim has flattened into the body.
                val t = (1f - d / bevel).coerceIn(0f, 1f)
                val f = t * t
                val sx = (left + x + 0.5f - x0) / 2f
                val sy = (top + y + 0.5f - y0) / 2f
                if (f > 0.002f) {
                    // Refraction: reach outward along the normal; blue a little further than red.
                    val r = reach * f * t / 2f
                    rgb[0] = sample(px, bw, bh, sx + nx * r, sy + ny * r, 16)
                    rgb[1] = sample(px, bw, bh, sx + nx * r * (1 + DISPERSION / 2), sy + ny * r * (1 + DISPERSION / 2), 8)
                    rgb[2] = sample(px, bw, bh, sx + nx * r * (1 + DISPERSION), sy + ny * r * (1 + DISPERSION), 0)
                } else {
                    sampleRgb(px, bw, bh, sx, sy, rgb)
                }
                row[x] = shade(rgb, light, f, nx, ny, d, max(0f, min(1f, d + 0.5f)))
            }
            if (side >= w) {
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            } else {
                bitmap.setPixels(row, 0, w, 0, y, side, 1)
                bitmap.setPixels(row, w - side, w, w - side, y, side, 1)
            }
        }
        return bitmap
    }

    /** Body tint, bevel light and the specular rim, applied to what the glass sees. */
    private fun shade(rgb: FloatArray, light: Boolean, f: Float, nx: Float, ny: Float, d: Float, alpha: Float): Int {
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]
        // A touch more colour, like the vibrancy of real glass (the weights of ColorMatrix.setSaturation).
        val l = 0.213f * r + 0.715f * g + 0.072f * b
        r = l + (r - l) * SATURATION
        g = l + (g - l) * SATURATION
        b = l + (b - l) * SATURATION
        if (light) {
            r = r * 0.62f + 255f * 0.38f
            g = g * 0.62f + 255f * 0.38f
            b = b * 0.62f + 255f * 0.38f
        } else {
            r = r * 0.88f + 255f * 0.07f
            g = g * 0.88f + 255f * 0.07f
            b = b * 0.88f + 255f * 0.07f
        }
        val facing = nx * LIGHT_X + ny * LIGHT_Y // 1 on the side toward the light, -1 opposite
        val lit = max(0f, facing)
        val away = max(0f, -facing)
        // Bevel: the upper rim lifts toward white, the base darkens a little; a soft Fresnel glow all round.
        val lift = f * (0.06f + 0.24f * lit * lit) * if (light) 0.6f else 1f
        r += (255f - r) * lift
        g += (255f - g) * lift
        b += (255f - b) * lift
        val dim = 1f - f * 0.16f * away
        r *= dim
        g *= dim
        b *= dim
        // Specular line along the very edge, about 1.5 px wide.
        val line = (1.6f - d).coerceIn(0f, 1f)
        if (line > 0f) {
            val spec = line * max(0.3f + 0.7f * lit * sqrt(lit), 0.55f * away * away) * if (light) 0.7f else 0.9f
            r += (255f - r) * spec
            g += (255f - g) * spec
            b += (255f - b) * spec
        }
        return ((alpha * 255f + 0.5f).toInt() shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
    }

    /** The backdrop as it is drawn on screen (center-cropped), cut to a region at half scale and blurred. */
    private fun behind(src: Bitmap, screenW: Float, screenH: Float, x0: Float, y0: Float, bw: Int, bh: Int): Bitmap {
        var scale = max(screenW / src.width, screenH / src.height)
        val dx = (screenW - src.width * scale) / 2f
        val dy = (screenH - src.height * scale) / 2f
        // Halve big sources first, so the half-scale copy averages pixels instead of skipping them.
        var img = src
        while (scale * 0.5f <= 0.5f && img.width > 2) {
            val half = Bitmap.createScaledBitmap(img, img.width / 2, img.height / 2, true)
            if (img !== src) img.recycle()
            img = half
            scale *= 2f
        }
        val view = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx - x0, dy - y0)
            postScale(0.5f, 0.5f)
        }
        Canvas(view).drawBitmap(img, m, Paint(Paint.FILTER_BITMAP_FLAG))
        if (img !== src) img.recycle()
        return boxBlur(view, radius = 4).also { view.recycle() }
    }

    /** One colour channel (bit [shift]) of the half-scale view, bilinear, clamped at its edges. */
    private fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float, shift: Int): Float {
        val u = (x - 0.5f).coerceIn(0f, w - 1f)
        val v = (y - 0.5f).coerceIn(0f, h - 1f)
        val ix = floor(u).toInt()
        val iy = floor(v).toInt()
        val fx = u - ix
        val fy = v - iy
        val jx = min(ix + 1, w - 1)
        val jy = min(iy + 1, h - 1)
        val a = (px[iy * w + ix] shr shift and 0xFF).toFloat()
        val b = (px[iy * w + jx] shr shift and 0xFF).toFloat()
        val c = (px[jy * w + ix] shr shift and 0xFF).toFloat()
        val d = (px[jy * w + jx] shr shift and 0xFF).toFloat()
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy
    }

    /** All three channels at one point: the flat body, where nothing is displaced. */
    private fun sampleRgb(px: IntArray, w: Int, h: Int, x: Float, y: Float, out: FloatArray) {
        val u = (x - 0.5f).coerceIn(0f, w - 1f)
        val v = (y - 0.5f).coerceIn(0f, h - 1f)
        val ix = floor(u).toInt()
        val iy = floor(v).toInt()
        val fx = u - ix
        val fy = v - iy
        val a = px[iy * w + ix]
        val b = px[iy * w + min(ix + 1, w - 1)]
        val c = px[min(iy + 1, h - 1) * w + ix]
        val d = px[min(iy + 1, h - 1) * w + min(ix + 1, w - 1)]
        var shift = 16
        for (i in 0..2) {
            val pa = (a shr shift and 0xFF).toFloat()
            val pb = (b shr shift and 0xFF).toFloat()
            val pc = (c shr shift and 0xFF).toFloat()
            val pd = (d shr shift and 0xFF).toFloat()
            out[i] = (pa + (pb - pa) * fx) * (1 - fy) + (pc + (pd - pc) * fx) * fy
            shift -= 8
        }
    }

    private fun sign(v: Float) = if (v < 0f) -1f else 1f

    private fun clamp8(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
}
