package com.andrej.glasstv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The TV apps opened most recently, most recent first: the ones started from the home screen, and,
 * while Glass's accessibility service is on, any app that comes to the front however it was
 * started (the remote's Netflix button, the Google Assistant). The inputs menu lists the first three
 * behind More. [skip] says which packages don't count: the TV's own player, which the inputs are.
 */
class RecentApps(private val context: Context, private val prefs: Prefs, private val apps: AppsRepository, private val skip: () -> Set<String>) {
    /** Debug: the first apps on the home screen stand in, to see the menu on an emulator nobody has used. */
    var demo = false

    // Whether a package is a TV app at all (a window coming up can be a dialog, the keyboard, a TCL panel).
    private val isTvApp = ConcurrentHashMap<String, Boolean>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    /** The recently opened apps that are still installed, most recent first. */
    val list: List<AppEntry>
        get() {
            val byPackage = apps.all.associateBy { it.packageName }
            val recent = prefs.recentApps.mapNotNull { byPackage[it] }
            return if (recent.isEmpty() && demo) apps.visible().take(3) else recent
        }

    /**
     * The TV app (or the TV's own player) in front, as far as Glass knows: null on the home screen.
     * Glass's own windows, dialogs, the keyboard and the TV's panels leave it as it is. Compose state,
     * so the iPhone app hears when it changes.
     */
    var front by mutableStateOf<String?>(null)

    /** [pkg] has come to the front. */
    fun opened(pkg: String) {
        if (pkg == context.packageName) return
        if (pkg in skip()) {
            front = pkg
            return
        }
        val tvApp = isTvApp.getOrPut(pkg) { context.packageManager.getLeanbackLaunchIntentForPackage(pkg) != null }
        if (!tvApp) return
        front = pkg
        val recent = prefs.recentApps
        if (recent.firstOrNull() == pkg) return
        prefs.recentApps = (listOf(pkg) + recent.filter { it != pkg }).take(KEEP)
    }

    /** The app used before the one in front (on the home screen, the one used last), for the control center. */
    fun previous(): AppEntry? = list.firstOrNull { it.packageName != front }

    /** The app's icon as a circle [px] across, for the inputs menu; made once, off the UI thread. */
    suspend fun icon(pkg: String, px: Int): ImageBitmap? = icons[pkg] ?: withContext(Dispatchers.Default) {
        runCatching { roundIcon(pkg, px) }.getOrNull()?.also { icons[pkg] = it }
    }

    private fun roundIcon(pkg: String, px: Int): ImageBitmap {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val square = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        // An adaptive icon fills the circle; an old-style one (often a logo on transparency) sits
        // inside it, on white, unless it is a full square picture of its own.
        if (drawable is AdaptiveIconDrawable) {
            val bleed = px / 6
            drawable.setBounds(-bleed, -bleed, px + bleed, px + bleed)
            drawable.draw(canvas)
        } else {
            val probe = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(probe))
            val corners = listOf(probe.getPixel(1, 1), probe.getPixel(px - 2, 1), probe.getPixel(1, px - 2), probe.getPixel(px - 2, px - 2))
            if (corners.all { Color.alpha(it) > 240 }) {
                canvas.drawBitmap(probe, 0f, 0f, null)
            } else {
                canvas.drawColor(Color.WHITE)
                val inset = px * 18 / 100
                drawable.setBounds(inset, inset, px - inset, px - inset)
                drawable.draw(canvas)
            }
            probe.recycle()
        }
        // Cut to a circle with a smooth edge.
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(px / 2f, px / 2f, px / 2f, paint)
        square.recycle()
        return out.asImageBitmap()
    }

    private companion object {
        const val KEEP = 8
    }
}
