package com.andrej.glasstv.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.andrej.glasstv.ui.home.Grid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The wallpaper plus pre-blurred variants of it.
 *
 * The TV runs Android 12 (no RuntimeShader) on a small PowerVR GPU, so live backdrop blur is too
 * expensive. Since everything glass sits on the wallpaper, glass surfaces sample these small
 * pre-blurred bitmaps instead; the GPU's bilinear upscale makes them look like a 60px blur for free.
 */
@Immutable
class Backdrop(
    val id: String,
    val sharp: ImageBitmap,
    /** The background of the app grid: light frosted glass over the art, with a sheen ([frostedPane]). */
    val blurred: ImageBitmap,
    /** Plain blur: what a Top Shelf passes through while it dissolves into the next one. */
    val soft: ImageBitmap,
    /** Blur with boosted saturation and brightness: the body of Liquid Glass surfaces. */
    val frost: ImageBitmap,
    /** Frost mixed with white: the fill of the glass clock numerals. */
    val clockFill: ImageBitmap,
    /** The dock's Liquid Glass over this backdrop, rendered with refraction ([DockGlass]). */
    val dock: ImageBitmap,
    /** The backdrop under the dock is bright, so the dock is milky white glass. */
    val dockLight: Boolean,
    /** Relative luminance on a coarse 16x9 grid, for adapting text and glass to what's behind them. */
    private val luma: FloatArray,
) {
    /** The area behind the clock is bright (sky, snow): the clock needs a stronger shadow. */
    val bright: Boolean get() = isBright(0.3f, 0.08f, 0.7f, 0.45f)

    /** Mean luminance of a region given in fractions of the screen is above [threshold]. */
    fun isBright(left: Float, top: Float, right: Float, bottom: Float, threshold: Float = 0.55f): Boolean =
        meanLuma(luma, left, top, right, bottom) > threshold

    /** Maps image pixels onto the screen with a center-crop, the same way the wallpaper is drawn. */
    fun coverMatrix(image: ImageBitmap, screen: Size, out: Matrix = Matrix()): Matrix {
        val scale = max(screen.width / image.width, screen.height / image.height)
        out.setScale(scale, scale)
        out.postTranslate((screen.width - image.width * scale) / 2f, (screen.height - image.height * scale) / 2f)
        return out
    }
}

/**
 * The backdrop being shown. Not static: a static local recomposes everything under it when it
 * changes, and on the TV that was the whole home screen (16–28 ms) on the very frame each Top Shelf
 * dissolve began. Only what reads it recomposes now; on the home screen glass and the dock follow
 * [LocalBackdropTransition] instead, without recomposing at all.
 */
val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

class WallpaperStore(private val context: Context) {
    var backdrop by mutableStateOf<Backdrop?>(null)
        private set

    val stills = AerialStills(context)
    private val mutex = Mutex()

    /** Shows a wallpaper. "aerial" keeps the current one (or a generated one) until today's photo is ready. */
    suspend fun load(id: String) {
        if (id != AERIAL) {
            show(id) { decodeAsset(id) }
            return
        }
        val today = stills.forDay(0)
        val key = "$AERIAL:${today.id}"
        if (backdrop?.id != key) {
            val cached = stills.cached(today)
            if (cached == null && backdrop == null) show(FALLBACK) { decodeAsset(FALLBACK) }
            val photo = cached ?: stills.fetch(today)
            if (photo != null) show(key) { decode { BitmapFactory.decodeFile(photo.path, it) } }
            else if (backdrop == null) show(FALLBACK) { decodeAsset(FALLBACK) }
        }
        stills.fetch(stills.forDay(1)) // so tomorrow's switch is instant
    }

    private suspend fun show(key: String, source: () -> Bitmap?) = mutex.withLock {
        if (backdrop?.id == key) return@withLock
        withContext(Dispatchers.Default) { source()?.let { build(key, it) } }?.let { backdrop = it }
    }

    private fun decodeAsset(id: String): Bitmap? {
        val name = WALLPAPERS.firstOrNull { it.id == id && it.id != AERIAL }?.id ?: FALLBACK
        return context.assets.open("wallpapers/$name.jpg").use { stream -> decode { BitmapFactory.decodeStream(stream, null, it) } }
    }

    private fun decode(block: (BitmapFactory.Options) -> Bitmap?) =
        block(BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })

    private fun build(id: String, src: Bitmap) = backdropOf(id, src)

    companion object {
        const val AERIAL = "aerial"
        private const val FALLBACK = "tahoe"
    }
}

/**
 * Builds the sharp image and its blurred variants. Runs off the main thread.
 *
 * @param dockCache where this backdrop's dock glass is kept between runs: rendering it is most of
 *   the work, and the TV closes the launcher whenever an app needs its memory.
 */
fun backdropOf(id: String, src: Bitmap, dockCache: File? = null): Backdrop {
    val small = boxBlur(downscale(src, 384, 216), radius = 9)
    val luma = FloatArray(16 * 9) { i -> luminance(small, (i % 16) / 16f, (i / 16) / 9f, (i % 16 + 1) / 16f, (i / 16 + 1) / 9f) }
    val bright = luminance(small, 0.3f, 0.08f, 0.7f, 0.45f) > 0.55f
    val frost = colorize(small, saturation = 1.3f, brightness = 1.06f, white = 0.04f)
    val clockFill = if (bright) {
        colorize(small, saturation = 1.35f, brightness = 0.96f, white = 0.3f)
    } else {
        colorize(small, saturation = 1.5f, brightness = 1.1f, white = 0.42f)
    }
    val dock = Grid.dock
    val dockLight = meanLuma(luma, dock.left / 960f, dock.top / 540f, dock.right / 960f, dock.bottom / 540f) > 0.55f
    val cached = dockCache?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
    val dockGlass = cached ?: DockGlass.render(src, dockLight).also { g -> dockCache?.let { saveDock(g, it) } }
    // The lower part darkened a little so the dock and labels stay legible on any art (less on
    // light art, where the dock is milky glass). Baked into the pictures rather than drawn over
    // them: a full-width gradient on every frame cost the TV's GPU more than anything it showed.
    val shade = if (dockLight) SHADE * 0.35f else SHADE
    val sharp = shadeBottom(src, shade)
    val soft = shadeBottom(small, shade)
    val gridBackground = frostedPane(small)
    return Backdrop(
        id, sharp.onGpu(), gridBackground.onGpu(), soft.onGpu(), frost.onGpu(),
        clockFill.onGpu(), dockGlass.onGpu(), dockLight, luma,
    )
}

/**
 * The bitmap uploaded to the GPU here, off the main thread. Drawn as an ordinary bitmap, each small
 * blur was uploaded by the render thread the first time a dissolve drew it (2–4 ms apiece on the
 * TV), right in the dissolve's first frames.
 */
private fun Bitmap.onGpu(): ImageBitmap {
    if (Build.VERSION.SDK_INT < 28) return asImageBitmap()
    val gpu = copy(Bitmap.Config.HARDWARE, false) ?: return asImageBitmap()
    recycle()
    return gpu.asImageBitmap()
}

private fun saveDock(glass: Bitmap, file: File) {
    val tmp = File(file.path + ".tmp")
    runCatching {
        tmp.outputStream().use { glass.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(file)
    }.onFailure { tmp.delete() }
}

/** How dark the bottom edge gets behind the dock, over dark art. */
const val SHADE = 0.35f

/** A copy of [src] fading to [strength] black from 55% of its height down. */
private fun shadeBottom(src: Bitmap, strength: Float): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val h = out.height.toFloat()
    Canvas(out).drawRect(0f, h * 0.55f, out.width.toFloat(), h, Paint().apply {
        shader = LinearGradient(0f, h * 0.55f, 0f, h, 0, (strength * 255).toInt() shl 24, Shader.TileMode.CLAMP)
    })
    return out
}

private fun meanLuma(luma: FloatArray, left: Float, top: Float, right: Float, bottom: Float): Float {
    var sum = 0f
    var n = 0
    for (y in (top * 9).toInt().coerceIn(0, 8)..(bottom * 9).toInt().coerceIn(0, 8)) {
        for (x in (left * 16).toInt().coerceIn(0, 15)..(right * 16).toInt().coerceIn(0, 15)) {
            sum += luma[y * 16 + x]
            n++
        }
    }
    return if (n > 0) sum / n else 0f
}

class WallpaperInfo(val id: String, val name: String)

val WALLPAPERS = listOf(
    WallpaperInfo(WallpaperStore.AERIAL, "Apple Aerial"),
    WallpaperInfo("tahoe", "Tahoe"),
    WallpaperInfo("dusk", "Dusk"),
    WallpaperInfo("sunrise", "Sunrise"),
    WallpaperInfo("aurora", "Aurora"),
    WallpaperInfo("graphite", "Graphite"),
    WallpaperInfo("midnight", "Midnight"),
)

private fun downscale(src: Bitmap, w: Int, h: Int): Bitmap {
    var b = src
    while (b.width / 2 >= w) {
        val next = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        if (b !== src) b.recycle()
        b = next
    }
    return Bitmap.createScaledBitmap(b, w, h, true)
}

/** Mean relative luminance (0..1) of a region given in fractions of the image. */
private fun luminance(b: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Float {
    val x0 = (b.width * left).toInt()
    val y0 = (b.height * top).toInt()
    val w = (b.width * (right - left)).toInt()
    val h = (b.height * (bottom - top)).toInt()
    val px = IntArray(w * h).also { b.getPixels(it, 0, w, x0, y0, w, h) }
    var sum = 0.0
    for (p in px) sum += 0.2126 * (p shr 16 and 0xFF) + 0.7152 * (p shr 8 and 0xFF) + 0.0722 * (p and 0xFF)
    return (sum / px.size / 255.0).toFloat()
}

/**
 * The app grid's background: light frosted glass over the Top Shelf. The art's colours show through,
 * softened and lifted toward white, with the sheen of a pane catching light from the top left.
 * Baked into the small blurred bitmap, so the glass costs nothing to draw.
 */
private fun frostedPane(src: Bitmap): Bitmap {
    val out = colorize(src, saturation = 1.45f, brightness = 1.02f, white = 0.64f)
    val w = out.width.toFloat()
    val h = out.height.toFloat()
    val canvas = Canvas(out)
    // Light falling on the pane from the top left...
    canvas.drawRect(0f, 0f, w, h, Paint().apply {
        shader = LinearGradient(0f, 0f, w * 0.55f, h, 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    })
    // ...a broad, faint reflection across it...
    canvas.drawRect(0f, 0f, w, h, Paint().apply {
        shader = LinearGradient(w * 0.3f, 0f, w * 0.8f, h, intArrayOf(0, 0x24FFFFFF, 0), floatArrayOf(0.3f, 0.5f, 0.7f), Shader.TileMode.CLAMP)
    })
    // ...and its lower part a little deeper, so the grid has depth.
    canvas.drawRect(0f, h * 0.55f, w, h, Paint().apply {
        shader = LinearGradient(0f, h * 0.55f, 0f, h, 0x00000000, 0x1A000000, Shader.TileMode.CLAMP)
    })
    return out
}

private fun colorize(src: Bitmap, saturation: Float, brightness: Float, white: Float): Bitmap {
    val m = ColorMatrix().apply { setSaturation(saturation) }
    m.postConcat(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
    if (white > 0f) {
        val k = 1f - white
        val t = 255f * white
        m.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    }
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    return out
}

/** Three box-blur passes approximate a Gaussian. Works on opaque images. */
internal fun boxBlur(src: Bitmap, radius: Int, passes: Int = 3): Bitmap {
    val w = src.width
    val h = src.height
    val a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    val b = IntArray(w * h)
    repeat(passes) {
        blurPass(a, b, w, h, radius, horizontal = true)
        blurPass(b, a, w, h, radius, horizontal = false)
    }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}

private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val step = if (horizontal) 1 else w
    val div = 2 * r + 1
    for (line in 0 until lines) {
        val start = if (horizontal) line * w else line
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val p = src[start + i.coerceIn(0, len - 1) * step]
            sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF
        }
        for (i in 0 until len) {
            dst[start + i * step] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = src[start + min(i + r + 1, len - 1) * step]
            val rem = src[start + max(i - r, 0) * step]
            sr += (add shr 16 and 0xFF) - (rem shr 16 and 0xFF)
            sg += (add shr 8 and 0xFF) - (rem shr 8 and 0xFF)
            sb += (add and 0xFF) - (rem and 0xFF)
        }
    }
}
