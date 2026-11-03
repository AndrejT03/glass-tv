package com.andrej.glasstv.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
class AppEntry(
    val key: String,
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
    val system: Boolean,
    /** 5:3 tvOS-style tile artwork, as big as a fully focused tile on this screen (so never upscaled). */
    val art: ImageBitmap,
    /** Last install/update time, so derived artwork (the Top Shelf) is rebuilt after updates. */
    val updated: Long = 0L,
) {
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** @param artWidth tile artwork width in pixels: a fully focused tile on this screen. */
class AppsRepository(private val context: Context, private val prefs: Prefs, private val artWidth: Int) {
    var all by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    /** Debug builds only: pads the grid with placeholder tiles to preview the layout on an emulator. */
    var demo = false

    private val mutex = Mutex()
    private val artCache = ConcurrentHashMap<String, ImageBitmap>()
    private val artHeight = artWidth * 3 / 5

    /** Packages with sharp 800x480 tile art in assets/tiles, rebuilt from their banners by tools/make_tiles.py. */
    private val designed: Set<String> by lazy {
        context.assets.list("tiles")?.mapTo(HashSet()) { it.substringBeforeLast('.') }.orEmpty()
    }

    suspend fun reload() = mutex.withLock {
        all = query()
    }

    /** Apps in the user's order. New apps go to the end, like on tvOS. */
    fun visible(): List<AppEntry> {
        val byKey = all.associateBy { it.key }
        val ordered = prefs.order.mapNotNull { byKey[it] }
        val known = prefs.order.toSet()
        return ordered + all.filter { it.key !in known }
    }

    fun saveOrder(visible: List<AppEntry>) {
        prefs.order = visible.map { it.key }
    }

    /**
     * The TV apps, with their tile art. The art is drawn on all cores at once: one tile after another
     * kept the dock empty for about a second whenever the launcher started again (after a reboot, or
     * after the TV closed it to free memory for an app).
     */
    private suspend fun query(): List<AppEntry> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val tv = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val seen = HashSet<String>()
        val entries = tv
            .filter { ri -> ri.activityInfo.packageName != context.packageName && seen.add(ri.activityInfo.packageName) }
            .map { ri ->
                async {
                    val ai = ri.activityInfo
                    val component = ComponentName(ai.packageName, ai.name)
                    val key = component.flattenToShortString()
                    val updated = runCatching { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                    val art = artCache.getOrPut("$key@$updated") { renderArt(pm, ri, leanback = true).asImageBitmap() }
                    AppEntry(
                        key = key,
                        packageName = ai.packageName,
                        label = ri.loadLabel(pm).toString(),
                        component = component,
                        leanback = true,
                        system = ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        art = art,
                        updated = updated,
                    )
                }
            }
            .awaitAll()
            .toMutableList()
        if (demo) entries += demoEntries()
        entries.sortedWith(compareBy({ priority(it.packageName) }, { it.label.lowercase() }))
    }

    private fun demoEntries(): List<AppEntry> {
        val typeface = androidx.core.content.res.ResourcesCompat.getFont(context, com.andrej.glasstv.R.font.inter_display_bold)
        return DEMO_NAMES.mapIndexed { i, name ->
            val bmp = Bitmap.createBitmap(artWidth, artHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val hue = (i * 47f + 200f) % 360f
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, artWidth.toFloat(), artHeight.toFloat(),
                    Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f)), Color.HSVToColor(floatArrayOf((hue + 30f) % 360f, 0.75f, 0.45f)),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, artWidth.toFloat(), artHeight.toFloat(), paint)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = artWidth * 0.115f
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(name, artWidth / 2f, artHeight / 2f + artWidth * 0.04f, text)
            AppEntry("demo/$i", context.packageName, name, ComponentName(context.packageName, "demo.$i"), true, false, bmp.asImageBitmap())
        }
    }

    private fun priority(pkg: String): Int {
        // "preview." packages are the emulator stand-ins built by tools/make_stubs.py.
        val i = PREFERRED.indexOf(pkg.removePrefix("preview."))
        return when {
            i >= 0 -> i
            pkg == "com.android.tv.settings" -> 10_000
            else -> 1_000
        }
    }

    private fun renderArt(pm: PackageManager, ri: ResolveInfo, leanback: Boolean): Bitmap {
        val bmp = designedArt(ri.activityInfo.packageName) ?: drawnArt(pm, ri, leanback)
        if (Build.VERSION.SDK_INT < 28) return bmp
        return bmp.copy(Bitmap.Config.HARDWARE, false).also { bmp.recycle() }
    }

    /** The app's sharp tile art, halved step by step down to size so it stays as crisp as the source. */
    private fun designedArt(pkg: String): Bitmap? {
        val name = tileName(pkg).takeIf { it in designed } ?: return null
        var b = runCatching { context.assets.open("tiles/$name.webp").use(BitmapFactory::decodeStream) }.getOrNull() ?: return null
        while (b.width / 2 >= artWidth) {
            val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            b.recycle()
            b = half
        }
        if (b.width == artWidth && b.height == artHeight) return b
        return Bitmap.createScaledBitmap(b, artWidth, artHeight, true).also { b.recycle() }
    }

    private fun drawnArt(pm: PackageManager, ri: ResolveInfo, leanback: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(artWidth, artHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val banner = if (leanback) runCatching { ri.activityInfo.loadBanner(pm) }.getOrNull() else null
        if (banner != null) {
            canvas.drawColor(0xFF1C1C1E.toInt())
            // Android banners are 16:9; tvOS icons are 5:3, so cover-crop the few percent at the sides.
            val iw = banner.intrinsicWidth.takeIf { it > 0 } ?: 320
            val ih = banner.intrinsicHeight.takeIf { it > 0 } ?: 180
            val scale = max(artWidth / iw.toFloat(), artHeight / ih.toFloat())
            val w = (iw * scale).roundToInt()
            val h = (ih * scale).roundToInt()
            banner.setBounds((artWidth - w) / 2, (artHeight - h) / 2, (artWidth + w) / 2, (artHeight + h) / 2)
            banner.draw(canvas)
        } else {
            val icon = ri.loadIcon(pm)
            val hsv = FloatArray(3)
            Color.colorToHSV(averageColor(icon), hsv)
            hsv[1] = (hsv[1] * 1.1f).coerceAtMost(0.85f)
            val top = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.9f, (hsv[2] + 0.12f).coerceIn(0.4f, 0.85f)))
            val bottom = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], (hsv[2] - 0.12f).coerceIn(0.18f, 0.6f)))
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, artHeight.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, artWidth.toFloat(), artHeight.toFloat(), paint)
            val s = (artHeight * 0.56f).roundToInt()
            icon.setBounds((artWidth - s) / 2, (artHeight - s) / 2, (artWidth + s) / 2, (artHeight + s) / 2)
            icon.draw(canvas)
        }
        return bmp
    }

    private fun averageColor(d: Drawable): Int {
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 24, 24)
        d.draw(Canvas(bmp))
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (p in IntArray(24 * 24).also { bmp.getPixels(it, 0, 24, 0, 0, 24, 24) }) {
            val a = p ushr 24
            if (a < 128) continue
            r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
        }
        bmp.recycle()
        if (n == 0L) return 0xFF3A3A3C.toInt()
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private companion object {
        val DEMO_NAMES = listOf(
            "Филмови", "Серии", "Музика", "Фотографии", "Спорт", "Вести", "Подкасти", "Игри",
            "Фитнес", "Готвење", "Патувања", "Документарци", "Деца", "Радио", "Прогноза", "Камери",
        )
        val PREFERRED = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.apple.atve.androidtv.appletv",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "com.spotify.tv.android",
            "com.google.android.youtube.tvmusic",
            "com.stremio.one",
            "org.xbmc.kodi",
            "com.google.android.videos",
            "com.android.vending",
        )
    }
}
