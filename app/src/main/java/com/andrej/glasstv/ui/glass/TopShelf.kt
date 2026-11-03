package com.andrej.glasstv.ui.glass

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.data.shelfName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * tvOS Top Shelf backgrounds for Android TV apps.
 *
 * On Apple TV every app supplies full-screen Top Shelf art. Android TV has no equivalent (the home
 * screen channels in TvProvider are readable only by Google's own launcher), but every TV app ships
 * a banner: its logo on its brand background, usually only 320x180. [TopShelfRenderer] rebuilds it
 * full-screen; this store caches the result on disk and the derived [Backdrop]s in memory.
 */
class TopShelfStore(private val context: Context) {
    private val dir = File(context.filesDir, "topshelf").apply { mkdirs() }
    private val files = Mutex()
    private val builds = Mutex()
    // Room for the whole dock, so moving along it never rebuilds a shelf (and its dock glass).
    private val backdrops = object : LinkedHashMap<String, Backdrop>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Backdrop>?) = size > 6
    }

    /** Packages with a hand-designed Top Shelf in assets/topshelf (exported from the design canvas). */
    private val designed: Set<String> by lazy {
        context.assets.list("topshelf")?.mapTo(HashSet()) { it.removeSuffix(".jpg") }.orEmpty()
    }

    suspend fun backdrop(app: AppEntry): Backdrop? {
        val key = key(app)
        synchronized(backdrops) { backdrops[key] }?.let { return it }
        // One at a time, so the focused app's shelf and the ones prepared ahead never build twice.
        return builds.withLock {
            synchronized(backdrops) { backdrops[key] }?.let { return@withLock it }
            val backdrop = withContext(Dispatchers.Default) {
                val start = SystemClock.elapsedRealtime()
                val image = designed(app) ?: file(app)?.let { BitmapFactory.decodeFile(it.path) }
                image?.let { backdropOf("shelf:$key", it, dockFile(key)) }
                    ?.also { Log.i(TAG, "Shelf backdrop for ${app.packageName} in ${SystemClock.elapsedRealtime() - start} ms") }
            } ?: return@withLock null
            synchronized(backdrops) { backdrops[key] = backdrop }
            backdrop
        }
    }

    /**
     * Gets the top row ready ahead of time, the focused app first: its shelf picture, then the
     * backdrop built from it (the blurs, the colours, the dock's glass), which on the TV takes
     * longer than the dissolve itself. Moving along the dock then never waits for one, also when
     * the launcher comes back after the TV closed it to free memory for an app.
     */
    suspend fun prepare(apps: List<AppEntry>) {
        apps.filter { designedName(it) !in designed }.forEach { file(it) }
        apps.forEach { backdrop(it) }
    }

    private fun designed(app: AppEntry): Bitmap? {
        val name = designedName(app).takeIf { it in designed } ?: return null
        return runCatching { context.assets.open("topshelf/$name.jpg").use(BitmapFactory::decodeStream) }
            .onFailure { Log.w(TAG, "Bad designed Top Shelf for $name", it) }
            .getOrNull()
    }

    private fun designedName(app: AppEntry) = shelfName(app.packageName)

    private suspend fun file(app: AppEntry): File? = files.withLock {
        val f = File(dir, "${key(app)}.jpg")
        if (f.exists()) return@withLock f
        withContext(Dispatchers.Default) {
            val start = SystemClock.elapsedRealtime()
            val image = runCatching { TopShelfRenderer.render(context, app.component) }
                .onFailure { Log.w(TAG, "No Top Shelf for ${app.packageName}", it) }
                .getOrNull() ?: return@withContext null
            Log.d(TAG, "Top Shelf for ${app.packageName} in ${SystemClock.elapsedRealtime() - start} ms")
            // Older versions of this app's shelf go first; then write and move into place.
            dir.listFiles()?.filter { it.name.startsWith("${app.packageName}@") }?.forEach { it.delete() }
            val tmp = File(dir, "${key(app)}.tmp")
            tmp.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            image.recycle()
            tmp.renameTo(f)
            f
        }
    }

    private fun key(app: AppEntry) = "${app.packageName}@${app.updated}"

    /**
     * The dock glass rendered for a shelf, kept until either app changes: the shelf's app (in its
     * key) or this one (a new version may draw the dock differently).
     */
    private fun dockFile(key: String) = File(dir, "$key~$generation.dock.png")

    private val generation: Long by lazy {
        val own = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        // Dock glass from earlier versions of either app goes.
        dir.listFiles()?.filter { it.name.endsWith(".dock.png") && !it.name.contains("~$own.") }?.forEach { it.delete() }
        own
    }

    private companion object {
        const val TAG = "TopShelf"
    }
}

/**
 * Turns a small banner into a crisp full-screen image. The brand background is modelled without
 * the logo (a blur that ignores logo pixels) and stretched to the screen edges. The logo becomes an
 * alpha mask that is upscaled with sharpened edges, the way signed-distance text is rendered, so a
 * 320 px raster logo stays sharp at 4x like a vector one.
 */
internal object TopShelfRenderer {
    private const val W = 1920
    private const val H = 1080

    fun render(context: Context, component: ComponentName): Bitmap? {
        val pm = context.packageManager
        val info = pm.getActivityInfo(component, 0)
        val res = pm.getResourcesForApplication(info.applicationInfo)
        val bannerId = if (info.banner != 0) info.banner else info.applicationInfo.banner
        val iconId = if (info.icon != 0) info.icon else info.applicationInfo.icon
        val banner = if (bannerId != 0) source(res, bannerId, 640, 360) else null
        val src = banner ?: source(res, iconId, 432, 432) ?: return null
        return compose(src, square = banner == null || src.width < src.height * 1.3f)
    }

    /** The resource's own pixels at its best density (no framework upscaling); vectors are rasterised. */
    private fun source(res: Resources, id: Int, maxW: Int, maxH: Int): Bitmap? {
        val value = TypedValue()
        val path = runCatching {
            res.getValueForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, value, true)
            value.string?.toString()
        }.getOrNull()
        if (path != null && !path.endsWith(".xml")) {
            val bitmap = runCatching {
                res.assets.openNonAssetFd(value.assetCookie, path).createInputStream().use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                }
            }.getOrNull()
            if (bitmap != null) return fit(bitmap, maxW, maxH)
        }
        val d = runCatching { res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull() ?: return null
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: maxW
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: maxH
        val scale = min(maxW / iw.toFloat(), maxH / ih.toFloat())
        val bmp = Bitmap.createBitmap((iw * scale).roundToInt(), (ih * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bmp.width, bmp.height)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun fit(b: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val scale = min(1f, min(maxW / b.width.toFloat(), maxH / b.height.toFloat()))
        if (scale >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt(), (b.height * scale).roundToInt(), true)
    }

    private fun compose(src: Bitmap, square: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val rgb = flatten(px, w, h)

        // Background model that ignores the logo, refined a few times.
        val r = max(4, w / 12)
        var bg = blur(rgb, FloatArray(n) { 1f }, w, h, r)
        val a = FloatArray(n)
        repeat(3) {
            mask(rgb, bg, a)
            bg = blur(rgb, weights(a), w, h, r)
        }
        mask(rgb, bg, a)
        dropShapesTouchingEdges(a, w, h)
        val smooth = blur(rgb, weights(a), w, h, max(6, w / 6))
        val logo = FloatArray(n * 3) { i ->
            val p = i / 3
            (bg[i] + (rgb[i] - bg[i]) / max(a[p], 0.08f)).coerceIn(0f, 1f)
        }

        val s = if (square) H * 0.40f / h else W * 0.62f / w
        val tw = (w * s).roundToInt()
        val th = (h * s).roundToInt()
        val x0 = (W - tw) / 2f
        val y0 = H * 0.40f - th / 2f

        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // Brand background, extended past the banner's edges to the whole screen.
        val bgBitmap = toBitmap(smooth, w, h)
        filter.shader = BitmapShader(bgBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(x0, y0) })
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), filter)
        filter.shader = null

        if (a.sum() < n * 0.004f) {
            // Not a logo on a background (a photo, say): show it as a rounded card instead.
            val card = RectF(x0, y0, x0 + tw, y0 + th)
            val radius = th * 0.08f
            filter.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(x0, y0) })
            }
            canvas.drawRoundRect(card, radius, radius, filter)
            filter.shader = null
        } else {
            canvas.drawBitmap(logoLayer(a, logo, w, h, tw, th, s), x0, y0, filter)
        }

        // A soft vignette, like the light falloff on tvOS Top Shelf art.
        val vignette = Paint().apply {
            shader = RadialGradient(W / 2f, H * 0.42f, W * 0.78f, intArrayOf(0, 0x2E000000), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), vignette)
        return out
    }

    /** RGB in 0..1, with transparency flattened onto the colour of the opaque border. */
    private fun flatten(px: IntArray, w: Int, h: Int): FloatArray {
        var br = 0f
        var bgg = 0f
        var bb = 0f
        var cnt = 0
        var ar = 0f
        var ag = 0f
        var ab = 0f
        var acnt = 0
        for (i in px.indices) {
            val p = px[i]
            if (p ushr 24 < 128) continue
            val x = i % w
            val y = i / w
            val cr = (p shr 16 and 0xFF) / 255f
            val cg = (p shr 8 and 0xFF) / 255f
            val cb = (p and 0xFF) / 255f
            ar += cr; ag += cg; ab += cb; acnt++
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                br += cr; bgg += cg; bb += cb; cnt++
            }
        }
        if (cnt > 0) {
            br /= cnt; bgg /= cnt; bb /= cnt
        } else if (acnt > 0) {
            // An icon with transparent corners (adaptive icon): a deep version of its own colour.
            br = ar / acnt * 0.3f; bgg = ag / acnt * 0.3f; bb = ab / acnt * 0.3f
        }
        val out = FloatArray(px.size * 3)
        for (i in px.indices) {
            val p = px[i]
            val al = (p ushr 24) / 255f
            out[i * 3] = (p shr 16 and 0xFF) / 255f * al + br * (1 - al)
            out[i * 3 + 1] = (p shr 8 and 0xFF) / 255f * al + bgg * (1 - al)
            out[i * 3 + 2] = (p and 0xFF) / 255f * al + bb * (1 - al)
        }
        return out
    }

    private fun mask(rgb: FloatArray, bg: FloatArray, a: FloatArray) {
        for (p in a.indices) {
            val dr = rgb[p * 3] - bg[p * 3]
            val dg = rgb[p * 3 + 1] - bg[p * 3 + 1]
            val db = rgb[p * 3 + 2] - bg[p * 3 + 2]
            val dist = sqrt((dr * dr + dg * dg + db * db) / 3f)
            a[p] = ((dist - 0.03f) / 0.10f).coerceIn(0f, 1f)
        }
    }

    private fun weights(a: FloatArray) = FloatArray(a.size) { val v = 1f - a[it]; v * v + 1e-4f }

    /** Shapes connected to the banner's edges are background (glows, bands, watermarks), not logo. */
    private fun dropShapesTouchingEdges(a: FloatArray, w: Int, h: Int) {
        val queue = IntArray(w * h)
        val seen = BooleanArray(w * h)
        var head = 0
        var tail = 0
        for (i in a.indices) {
            val x = i % w
            val y = i / w
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && a[i] > 0.35f) {
                seen[i] = true
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            a[i] = 0f
            val x = i % w
            val y = i / w
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                if (j >= 0 && !seen[j] && a[j] > 0.2f) {
                    seen[j] = true
                    queue[tail++] = j
                }
            }
        }
    }

    /** Weighted (normalised) box blur, three passes, clamped at the edges. */
    private fun blur(rgb: FloatArray, weight: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val n = w * h
        val num = FloatArray(n * 4)
        for (p in 0 until n) {
            val k = weight[p]
            num[p * 4] = rgb[p * 3] * k
            num[p * 4 + 1] = rgb[p * 3 + 1] * k
            num[p * 4 + 2] = rgb[p * 3 + 2] * k
            num[p * 4 + 3] = k
        }
        val tmp = FloatArray(n * 4)
        repeat(3) {
            pass(num, tmp, w, h, r, horizontal = true)
            pass(tmp, num, w, h, r, horizontal = false)
        }
        return FloatArray(n * 3) { i -> num[(i / 3) * 4 + i % 3] / num[(i / 3) * 4 + 3] }
    }

    private fun pass(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 1f / (2 * r + 1)
        val sum = FloatArray(4)
        for (line in 0 until lines) {
            val start = if (horizontal) line * w else line
            sum.fill(0f)
            for (i in -r..r) {
                val p = (start + i.coerceIn(0, len - 1) * step) * 4
                for (c in 0..3) sum[c] += src[p + c]
            }
            for (i in 0 until len) {
                val o = (start + i * step) * 4
                for (c in 0..3) dst[o + c] = sum[c] * div
                val add = (start + min(i + r + 1, len - 1) * step) * 4
                val rem = (start + max(i - r, 0) * step) * 4
                for (c in 0..3) sum[c] += src[add + c] - src[rem + c]
            }
        }
    }

    /** The logo at its final size: bicubic alpha with sharpened edges, bilinear colour. */
    private fun logoLayer(a: FloatArray, logo: FloatArray, w: Int, h: Int, tw: Int, th: Int, s: Float): Bitmap {
        // Skip output pixels whose neighbourhood has no logo at all.
        val near = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (a[y * w + x] <= 0f) continue
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) near[ny * w + nx] = true
            }
        }
        val k = (0.6f / s).coerceAtMost(0.5f)
        val out = IntArray(tw * th)
        for (y in 0 until th) {
            val v = (y + 0.5f) / s - 0.5f
            for (x in 0 until tw) {
                val u = (x + 0.5f) / s - 0.5f
                val cx = u.roundToInt().coerceIn(0, w - 1)
                val cy = v.roundToInt().coerceIn(0, h - 1)
                if (!near[cy * w + cx]) continue
                val t = ((cubic(a, w, h, u, v) - (0.5f - k)) / (2 * k)).coerceIn(0f, 1f)
                val alpha = t * t * (3 - 2 * t)
                if (alpha <= 0.002f) continue
                val r = linear(logo, w, h, u, v, 0)
                val g = linear(logo, w, h, u, v, 1)
                val b = linear(logo, w, h, u, v, 2)
                out[y * tw + x] = ((alpha * 255).roundToInt() shl 24) or
                    ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
            }
        }
        return Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888)
    }

    private fun cubic(a: FloatArray, w: Int, h: Int, u: Float, v: Float): Float {
        val x = floor(u).toInt()
        val y = floor(v).toInt()
        val fx = u - x
        val fy = v - y
        var sum = 0f
        for (j in -1..2) {
            val wy = catmullRom(fy, j)
            val yy = (y + j).coerceIn(0, h - 1)
            for (i in -1..2) sum += a[yy * w + (x + i).coerceIn(0, w - 1)] * catmullRom(fx, i) * wy
        }
        return sum
    }

    private fun catmullRom(t: Float, i: Int): Float = when (i) {
        -1 -> (-t * t * t + 2 * t * t - t) / 2
        0 -> (3 * t * t * t - 5 * t * t + 2) / 2
        1 -> (-3 * t * t * t + 4 * t * t + t) / 2
        else -> (t * t * t - t * t) / 2
    }

    private fun linear(c: FloatArray, w: Int, h: Int, u: Float, v: Float, ch: Int): Float {
        val x = floor(u).toInt()
        val y = floor(v).toInt()
        val fx = u - x
        val fy = v - y
        fun at(xx: Int, yy: Int) = c[(yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)) * 3 + ch]
        val top = at(x, y) * (1 - fx) + at(x + 1, y) * fx
        val bottom = at(x, y + 1) * (1 - fx) + at(x + 1, y + 1) * fx
        return (top * (1 - fy) + bottom * fy).coerceIn(0f, 1f)
    }

    private fun toBitmap(rgb: FloatArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { p ->
            (0xFF shl 24) or ((rgb[p * 3] * 255).roundToInt().coerceIn(0, 255) shl 16) or
                ((rgb[p * 3 + 1] * 255).roundToInt().coerceIn(0, 255) shl 8) or (rgb[p * 3 + 2] * 255).roundToInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
