package com.andrej.glasstv.ui.home

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import com.andrej.glasstv.inputs.InputsList
import com.andrej.glasstv.inputs.drawCornerOf
import com.andrej.glasstv.inputs.drawCornerShade
import com.andrej.glasstv.inputs.shadeAt
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.glass.boxBlur
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The snapshot is this many pixels wide: blurred this much, nothing finer than that survives anyway. */
private const val SNAP_W = 240
private const val BLUR = 8

/** Dark glass: the blurred screen at this much of its brightness (and a touch lifted), its colours livelier. */
private const val DARK = 0.34f
private const val LIFT = 6f
private const val SATURATION = 1.35f

/** Clear glass (the control center's tiles): the blurred screen nearly as bright as it is. */
private const val CLEAR = 0.9f

/**
 * The dark frosted glass a menu sits on, in its corner of the home screen and fading out toward the
 * middle (the inputs menu's bottom left, by default): a snapshot of the home screen, blurred and
 * darkened. The TV can't blur live (no RuntimeShader, a small GPU), so the screen is copied once, at
 * 240 pixels across, when the menu opens, and blurred on the CPU, the corner's shape ([mask], 0..1
 * across and down the screen) going into its transparency; the GPU's bilinear upscale smooths it
 * the rest of the way.
 *
 * @param clear also keep the blurred screen undarkened and whole, as [glass], for glass tiles over the frost.
 */
@Stable
class Frost(internal val layer: GraphicsLayer, private val mask: (Float, Float) -> Float, private val clear: Boolean) {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** The blurred screen, whole and nearly as bright as it is; only with `clear`. */
    var glass by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 0..1: how far the frost has come in. */
    val amount = Animatable(0f)

    /**
     * The home screen under the menu is held as one texture: drawn once when the menu opens, then
     * only copied to the screen. Nothing on it moves meanwhile, and redrawing it (the Top Shelf, the
     * dock's glass) took the TV's GPU over 20 ms a frame while the list moved.
     */
    var holding by mutableStateOf(false)
        internal set

    internal var asked by mutableIntStateOf(0)
    internal var taken = 0
    internal var recorded: CompletableDeferred<Unit>? = null

    /** Copies the home screen on its next frame and turns it into frost (a few tens of ms on the TV). */
    suspend fun capture() {
        val done = CompletableDeferred<Unit>().also { recorded = it }
        asked++
        if (withTimeoutOrNull(400) { done.await() } == null) return
        val made = runCatching {
            val shot = layer.toImageBitmap().asAndroidBitmap()
            withContext(Dispatchers.Default) { frosted(shot, mask, clear) }
        }.getOrNull()
        image = made?.first
        glass = made?.second
    }

    fun clear() {
        image = null
        glass = null
    }
}

@Composable
fun rememberFrost(mask: (Float, Float) -> Float = ::shadeAt, clear: Boolean = false): Frost {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Frost(layer, mask, clear) }
}

/** The home screen under the frost: copied when the frost asks, and held as a texture while the menu is open. */
fun Modifier.frostSource(frost: Frost): Modifier = graphicsLayer {
    compositingStrategy = if (frost.holding) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}.drawWithContent {
    drawContent()
    if (frost.asked != frost.taken) {
        frost.taken = frost.asked
        val full = size
        val h = (SNAP_W * full.height / full.width).roundToInt().coerceAtLeast(1)
        frost.layer.record(IntSize(SNAP_W, h)) {
            scale(SNAP_W / full.width, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
        }
        frost.recorded?.complete(Unit)
    }
}

/** The frost, shaped by [mask], and with [clear] the blurred screen whole and undarkened too. */
private fun frosted(shot: Bitmap, mask: (Float, Float) -> Float, clear: Boolean): Pair<ImageBitmap, ImageBitmap?> {
    val soft = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
    val blurred = boxBlur(soft, radius = BLUR)
    val out = toned(blurred, DARK, LIFT)
    val w = out.width
    val h = out.height
    // Only the corner the menu sits in is frosted; the rest of the screen stays as it is.
    val px = IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    for (y in 0 until h) {
        for (x in 0 until w) {
            val a = (mask((x + 0.5f) / w, (y + 0.5f) / h) * 255f).roundToInt()
            val i = y * w + x
            px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
        }
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out.asImageBitmap() to if (clear) toned(blurred, CLEAR, 0f).asImageBitmap() else null
}

/** [image] at [k] of its brightness, lifted by [lift], its colours livelier. */
private fun toned(image: Bitmap, k: Float, lift: Float): Bitmap {
    val m = ColorMatrix().apply { setSaturation(SATURATION) }
    m.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, lift, 0f, k, 0f, 0f, lift, 0f, 0f, k, 0f, lift, 0f, 0f, 0f, 1f, 0f)))
    val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(image, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    return out
}

/**
 * The inputs menu on the home screen (the capsule's TV button, or the remote's Input button while
 * the home screen is in front): the bottom-left corner of the screen turns into dark frosted glass
 * and the inputs rise in it (see [InputsList]).
 */
@Composable
fun BoxScope.InputsMenu(c: HomeController, frost: Frost) {
    val open = c.panel == Panel.Inputs
    // The list in (or on its way out), and the menu there at all.
    var shown by remember { mutableStateOf(false) }
    var present by remember { mutableStateOf(false) }
    // Once opened, the list stays composed while hidden, as the one over other apps does: composing
    // it and measuring its text again took the TV's main thread ~100 ms at every opening.
    var composed by remember { mutableStateOf(false) }

    LaunchedEffect(open) {
        if (open) {
            present = true
            composed = true
            frost.holding = true
            frost.capture()
            launch { frost.amount.animateTo(1f, tween(240, easing = Motion.EaseOut)) }
            shown = true
        } else if (present) {
            shown = false
            delay(50)
            frost.amount.animateTo(0f, tween(220))
            frost.clear()
            frost.holding = false
            present = false
        }
    }
    if (present) {
        Canvas(Modifier.fillMaxSize()) {
            val a = frost.amount.value
            if (a <= 0.002f) return@Canvas
            val image = frost.image
            if (image != null) drawCornerOf(image, a) else drawCornerShade(0.8f * a)
        }
    }
    if (composed) InputsList(c.inputsMenu, shown)
}
