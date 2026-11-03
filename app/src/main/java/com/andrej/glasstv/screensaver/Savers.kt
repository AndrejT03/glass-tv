package com.andrej.glasstv.screensaver

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.DisplayFont
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.formatDate
import com.andrej.glasstv.ui.formatTime
import com.andrej.glasstv.ui.rememberMinute
import com.andrej.glasstv.ui.stringsFor
import java.util.Calendar
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

/** The screen savers, in the order the settings and the preview step through them. */
val SAVERS = listOf("aerial", "landscapes", "portraits", "dynamic", "liquid", "standby")

/** Which screen saver is showing; the remote changes it in the preview. */
@Stable
class SaverState(context: Context, mode: String) {
    var mode by mutableStateOf(if (mode == "aerial" && !isOnline(context)) "landscapes" else mode)

    /** Left/Right on the remote: the next or previous aerial or photo, as on Apple TV. */
    val skips = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Debug: shots, photos and clock layouts change every few seconds, to check them quickly. */
    var fast = false

    /** In the picker: OK has chosen the saver on screen. */
    var chosen by mutableStateOf(false)
}

/**
 * The screen saver on screen, Glass's own or the system's, for the iPhone app's next, previous and
 * stop: the same as Left, Right and any other button on the remote.
 */
object ShowingSaver {
    var state by mutableStateOf<SaverState?>(null)
        private set
    private var stop: (() -> Unit)? = null

    fun shown(state: SaverState, stop: () -> Unit) {
        this.state = state
        this.stop = stop
    }

    fun gone(state: SaverState) {
        if (this.state !== state) return
        this.state = null
        stop = null
    }

    fun skip(step: Int): Boolean = state?.skips?.tryEmit(step) == true

    fun stop(): Boolean = stop?.let { it(); true } ?: false
}

/** Picks the screen saver from settings; shared by the in-launcher saver and the system DreamService. */
@Composable
fun ScreensaverHost(state: SaverState? = null, preview: Boolean = false) {
    val context = LocalContext.current
    val prefs = GlassApp.instance.prefs
    val saver = state ?: remember { SaverState(context, prefs.saver) }
    CompositionLocalProvider(LocalStrings provides stringsFor(prefs.language)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Switching savers dips through black, like the tvOS screen saver picker.
            val dip = remember { Animatable(0f) }
            LaunchedEffect(saver.mode) {
                dip.snapTo(1f)
                dip.animateTo(0f, tween(700))
            }
            key(saver.mode) {
                when (saver.mode) {
                    "aerial" -> AerialSaver(
                        uhd = prefs.aerialQuality == "uhd",
                        clock = SaverClock.of(prefs.saverClock),
                        skips = saver.skips,
                        fast = saver.fast,
                        onFail = { saver.mode = "landscapes" },
                    )
                    "landscapes" -> LandscapesSaver(SaverClock.of(prefs.saverClock), saver.skips, saver.fast, onEmpty = { saver.mode = "liquid" })
                    "portraits" -> PortraitsSaver(SaverClock.of(prefs.saverClock), saver.skips, saver.fast, onEmpty = { saver.mode = "liquid" })
                    "dynamic" -> DynamicSaver(prefs.dynamicWallpaper, SaverClock.of(prefs.saverClock), saver.skips, saver.fast, onEmpty = { saver.mode = "liquid" })
                    "liquid" -> LiquidSaver(WAVE_PALETTES[prefs.wallpaper] ?: WAVE_PALETTES.getValue("tahoe"), SaverClock.of(prefs.saverClock))
                    else -> StandBySaver()
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = dip.value }.background(Color.Black))
            if (preview) PreviewHint(saver.mode, saver.chosen)
        }
    }
}

/**
 * The picker's caption: which saver this is and how to step through them. It shows when the
 * picker opens and after each switch, then fades away so the saver can be judged on its own.
 * Once OK has chosen one, it comes back with a check mark until the picker closes.
 */
@Composable
private fun BoxScope.PreviewHint(mode: String, chosen: Boolean) {
    val strings = LocalStrings.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(mode, chosen) {
        shown.animateTo(1f, tween(if (chosen) 200 else 350))
        if (chosen) return@LaunchedEffect
        delay(3500)
        shown.animateTo(0f, tween(900))
    }
    val arrows = if (chosen) 0f else 1f
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            // Above the aerials' location line.
            .padding(bottom = 80.dp)
            .graphicsLayer {
                alpha = shown.value
                translationY = (1f - shown.value) * 12.dp.toPx()
            }
            .background(Color(0xFF0B0B12).copy(alpha = 0.62f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("‹", Modifier.graphicsLayer { alpha = arrows }, style = HintArrow)
        Column(Modifier.padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(strings.savers[mode] ?: mode, style = HintTitle)
            if (chosen) {
                BasicText("✓  ${strings.chosen}", Modifier.padding(top = 2.dp), style = HintLine.copy(color = HintCheck))
            } else {
                BasicText(strings.previewHint, Modifier.padding(top = 2.dp), style = HintLine)
            }
        }
        BasicText("›", Modifier.graphicsLayer { alpha = arrows }, style = HintArrow)
    }
}

private val HintTitle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Color.White)
private val HintLine = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.65f))
private val HintCheck = Color(0xFF5BE37D)
private val HintArrow = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 30.sp, color = Color.White.copy(alpha = 0.85f))

/** macOS-style live wallpaper: the home wallpaper's glass waves, slowly moving. */
@Composable
fun LiquidSaver(palette: WavePalette, clock: SaverClock) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { LiquidGlView(it, palette) }, modifier = Modifier.fillMaxSize())
        when (clock) {
            SaverClock.Big -> TvClock(ClockLayout.Center)
            SaverClock.Small -> SmallClockOnTimer()
            SaverClock.Off -> Unit
        }
    }
}

/** iOS StandBy: a huge clock on black that turns red at night and drifts a little every minute. */
@Composable
fun StandBySaver() {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val now by rememberMinute()
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val night = hour >= 22 || hour < 7
    // Small per-minute drift against burn-in, eased so it never jumps.
    val minute = now / 60_000
    val dx by animateFloatAsState((sin(minute * 1.7) * 14).toFloat(), tween(4000), label = "dx")
    val dy by animateFloatAsState((sin(minute * 2.3 + 1) * 10).toFloat(), tween(4000), label = "dy")
    val fill = if (night) {
        Brush.verticalGradient(listOf(Color(0xFFFF5146), Color(0xFFB3261E)))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFDADCE6)))
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.offset(dx.dp, dy.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                formatDate(strings, now),
                style = TextStyle(
                    fontFamily = TextFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    color = if (night) Color(0xFFB3261E) else Color.White.copy(alpha = 0.7f),
                ),
            )
            BasicText(
                formatTime(context, now),
                Modifier.offset(y = (-14).dp),
                style = TextStyle(
                    brush = fill,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 250.sp,
                    letterSpacing = (-5).sp,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}
