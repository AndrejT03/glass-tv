package com.andrej.glasstv.controlcenter

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.ui.Glyph
import com.andrej.glasstv.ui.GlyphIcon
import com.andrej.glasstv.ui.Link
import com.andrej.glasstv.ui.LocalScreenPx
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.Palette
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.drawEthernet
import com.andrej.glasstv.ui.drawGlyph
import com.andrej.glasstv.ui.drawWifi
import com.andrej.glasstv.ui.formatTime
import com.andrej.glasstv.ui.rememberMinute
import com.andrej.glasstv.ui.rememberNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The grid, in design units (960x540): at the top right, where tvOS 26 has its Control Center.
private const val CELL = 66f
private const val GAP = 12f
private const val GRID_W = 4 * CELL + 3 * GAP
private const val GRID_RIGHT = 922f
private const val GRID_LEFT = GRID_RIGHT - GRID_W
private const val HEADER_TOP = 26f
private const val GRID_TOP = 76f
private const val GRID_BOTTOM = GRID_TOP + 5 * CELL + 4 * GAP

/** Corner radius of the two big squares; capsules and round buttons are fully round. */
private const val SQUARE_RADIUS = 30f

// The shade hugs the top-right corner: solid right of SHADE_LEFT and above SHADE_BOTTOM, fading
// out over SHADE_FADE beyond that.
private const val SHADE_LEFT = 590f
private const val SHADE_BOTTOM = 486f
private const val SHADE_FADE = 360f

/** How dark the shade is at its deepest, behind the tiles. */
const val CONTROL_SHADE = 0.8f

/**
 * The tiles' glass over other apps, where nothing can be blurred: dense enough that text in the app
 * behind doesn't read through it. The quick settings' buttons are made of it too.
 */
internal val SmokedGlass = Color(0xFF26262B).copy(alpha = 0.86f)

// The control center's Liquid Glass, which the quick settings are made of too: the blurred screen
// under it ([glassOver], on the home screen) or [SmokedGlass] over other apps, a little light over
// that ([GlassLight]), a sheen on its upper half ([GlassSheen]) and a lit rim ([glassRim]).

/** The light over the glass's body. */
internal val GlassLight = Color.White.copy(alpha = 0.1f)

/** The sheen on the glass's upper half. */
internal val GlassSheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.5f to Color.Transparent)

/** The glass's rim across a surface [size] px: brightest at the top-left, a second light at the bottom-right. */
internal fun glassRim(size: Size) = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.75f),
    0.3f to Color.White.copy(alpha = 0.2f),
    0.7f to Color.White.copy(alpha = 0.06f),
    1f to Color.White.copy(alpha = 0.34f),
    start = Offset.Zero,
    end = Offset(size.width, size.height),
)

/** How thick the glass's rim is. */
internal val GlassRimWidth = 1.1.dp

/** [frost] (the whole [screen], px, blurred) under glass whose top left is at [origin] px on it. */
internal fun glassOver(frost: ImageBitmap, screen: Size, origin: Offset) = ShaderBrush(
    ImageShader(frost).apply {
        setLocalMatrix(Matrix().apply {
            setScale(screen.width / frost.width, screen.height / frost.height)
            postTranslate(-origin.x, -origin.y)
        })
    },
)

/** How far from the right edge the shade reaches (0..1 of the screen): nothing left of it needs drawing. */
const val CONTROL_REACH = (960f - (SHADE_LEFT - SHADE_FADE)) / 960f

/** How deep the control center's shade is (0..1) at (u, v), 0..1 across and down the screen. */
fun controlShadeAt(u: Float, v: Float): Float {
    val dx = (SHADE_LEFT - u * 960f).coerceAtLeast(0f)
    val dy = (v * 540f - SHADE_BOTTOM).coerceAtLeast(0f)
    val t = (sqrt(dx * dx + dy * dy) / SHADE_FADE).coerceIn(0f, 1f)
    return 1f - t * t * t * (t * (t * 6f - 15f) + 10f)
}

private val ShadeImage: ImageBitmap by lazy {
    val w = 240
    val h = 135
    val px = IntArray(w * h) { i -> (controlShadeAt((i % w + 0.5f) / w, (i / w + 0.5f) / h) * 255f).roundToInt() shl 24 }
    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Draws the part of [image] (a picture of the whole screen) that the shade reaches, over the same
 * part of this screen-sized area: the TV's GPU pays for every pixel blended, so the left is skipped.
 */
fun DrawScope.drawControlShadeOf(image: ImageBitmap, alpha: Float) {
    if (alpha <= 0.002f) return
    val srcX = (image.width * (1f - CONTROL_REACH)).toInt()
    val dstX = (size.width * srcX / image.width).roundToInt()
    drawImage(
        image,
        srcOffset = IntOffset(srcX, 0),
        srcSize = IntSize(image.width - srcX, image.height),
        dstOffset = IntOffset(dstX, 0),
        dstSize = IntSize(size.width.roundToInt() - dstX, size.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** The plain shade, for where nothing can be blurred (over other apps). */
fun DrawScope.drawControlShade(alpha: Float) = drawControlShadeOf(ShadeImage, alpha)

private fun rectOf(t: Tile): Rect {
    val left = GRID_LEFT + t.col * (CELL + GAP)
    val top = GRID_TOP + t.row * (CELL + GAP)
    return Rect(left, top, left + t.w * CELL + (t.w - 1) * GAP, top + t.h * CELL + (t.h - 1) * GAP)
}

/** Each tile drops into place with a soft settle, from the top-right corner outwards. */
private val Rise = spring<Float>(dampingRatio = 0.76f, stiffness = 380f)
private const val STAGGER_MS = 24L

// The ping ring: good, slow, bad.
private val PingGood = Color(0xFF30D158)
private val PingSlow = Color(0xFFFFD60A)
private val PingBad = Color(0xFFFF453A)
private val RamColor = Color(0xFF0A84FF)
private val WifiColor = Color(0xFF0A84FF)
/** The light's disc while it is on: Apple's orange, a lamp that is lit. */
private val LitColor = Color(0xFFFF9F0A)

/**
 * The tvOS 26 Control Center, at the top right: the time, then Liquid Glass tiles on a grid of four
 * columns. Power Off and a live view of the TV's performance are big squares; Cable TV, Wi-Fi, Sound
 * and the app used last capsules; Bluetooth, the screen saver, energy saving and settings round
 * buttons.
 * The tile in focus turns into bright white glass with dark ink, as on Apple TV.
 *
 * @param frost the screen behind, blurred (on the home screen), which the tiles' glass shows; over
 *   other apps there is nothing to blur and the glass is a clear tint over the shade.
 */
@Composable
fun BoxScope.ControlCenterPanel(model: ControlCenterModel, shown: Boolean, frost: ImageBitmap?) {
    val glass = GlassApp.instance
    val context = LocalContext.current
    val strings = LocalStrings.current
    var stats by remember { mutableStateOf(Stats()) }
    var wifiName by remember { mutableStateOf<String?>(null) }
    // The app the round button goes back to, as it is when the control center opens (kept while it goes).
    var lastApp by remember { mutableStateOf<AppEntry?>(null) }
    // Live only while it is open: a reading a second, off the UI thread.
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        lastApp = glass.recents.previous()
        var first = true
        while (true) {
            stats = withContext(Dispatchers.IO) { glass.stats.sample() }
            wifiName = withContext(Dispatchers.IO) { Controls.wifiName(context) }
            glass.volume.refresh()
            // The network's speed needs two readings: the second comes soon.
            delay(if (first) 300 else 1_000)
            first = false
        }
    }
    val network by rememberNetwork()
    val ambient = model.ambient

    Header(shown)
    Tile.entries.forEach { tile ->
        key(tile) {
            // From the top-right corner outwards.
            val order = (3 - (tile.col + tile.w - 1)) + tile.row
            TileView(tile, focused = model.focus == tile, shown, 40L + order * STAGGER_MS, frost) { ink ->
                when (tile) {
                    Tile.Power -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlyphIcon(Glyph.Power, Modifier.size(50.dp)) { ink }
                        Spacer(Modifier.height(12.dp))
                        BasicText(strings.powerOff, style = LabelStyle.copy(color = ink), maxLines = 1)
                    }
                    Tile.Performance -> Performance(stats, ink)
                    Tile.Ambient -> {
                        val reached = ambient.link != AmbientLight.Link.Unreachable && ambient.link != AmbientLight.Link.NoPermission
                        Capsule(
                            draw = { drawGlyph(if (ambient.on) Glyph.LightbulbOn else Glyph.Lightbulb, it) },
                            disc = LitColor.takeIf { ambient.on && reached },
                            title = strings.ambientLight,
                            subtitle = when {
                                !reached -> strings.unavailable
                                ambient.on -> strings.on
                                else -> strings.off
                            },
                            ink = ink,
                        )
                    }
                    Tile.AmbientColor -> ColorCapsule(ambient, model.adjusting && model.focus == Tile.AmbientColor, ink)
                    Tile.Wifi -> Capsule(
                        draw = { color ->
                            when (network.link) {
                                Link.Ethernet -> drawEthernet(color)
                                Link.Wifi -> drawWifi(online = true, bars = network.bars, color = color)
                                Link.None -> drawWifi(online = false, bars = 0, color = color)
                            }
                        },
                        disc = WifiColor.takeIf { network.link != Link.None },
                        title = if (network.link == Link.Ethernet) strings.ethernet else "Wi-Fi",
                        subtitle = when (network.link) {
                            Link.None -> strings.offline
                            Link.Ethernet -> strings.connected
                            Link.Wifi -> wifiName ?: strings.connected
                        },
                        ink = ink,
                    )
                    Tile.Sound -> SoundCapsule(glass.volume, model.adjusting && model.focus == Tile.Sound, ink)
                    Tile.Bluetooth -> GlyphIcon(Glyph.Bluetooth, Modifier.size(25.dp)) { ink }
                    Tile.Screensaver -> GlyphIcon(Glyph.Screensaver, Modifier.size(25.dp)) { ink }
                    Tile.LastApp -> AppButton(lastApp, ink)
                    Tile.Settings -> GlyphIcon(Glyph.Gear, Modifier.size(26.dp)) { ink }
                }
            }
        }
    }

    // Under the tiles: the round button's name, how to change the volume or the colour, or why a tile
    // couldn't act.
    val focus = model.focus
    val caption = model.notice ?: when {
        model.adjusting && focus == Tile.AmbientColor -> strings.colorHint
        model.adjusting -> strings.volumeHint
        focus == Tile.Ambient && ambient.link == AmbientLight.Link.NoPermission -> strings.needsBluetooth
        focus == Tile.Ambient && ambient.link == AmbientLight.Link.Unreachable -> strings.stripAway
        focus == Tile.Bluetooth -> "Bluetooth"
        focus == Tile.Screensaver -> strings.screensaver
        focus == Tile.LastApp -> lastApp?.let { "${strings.lastApp}: ${it.label}" } ?: strings.lastApp
        focus == Tile.Settings -> strings.systemSettings
        else -> null
    }
    val last = remember { arrayOf("") }
    if (caption != null) last[0] = caption
    val captionAlpha by animateFloatAsState(if (shown && caption != null) 1f else 0f, tween(160), label = "ccCaption")
    BasicText(
        last[0],
        Modifier
            .offset { IntOffset(GRID_LEFT.dp.roundToPx(), (GRID_BOTTOM + 12f).dp.roundToPx()) }
            .width(GRID_W.dp)
            .graphicsLayer {
                alpha = captionAlpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        style = CaptionStyle,
        maxLines = 1,
    )
}

/** The time at the left, and the Control Center symbol on white at the right, as in tvOS 26. */
@Composable
private fun Header(shown: Boolean) {
    val context = LocalContext.current
    val now by rememberMinute()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) appear.animateTo(1f, Rise) else appear.animateTo(0f, tween(130, easing = FastOutLinearInEasing))
    }
    Box(
        Modifier
            .offset { IntOffset(GRID_LEFT.dp.roundToPx(), HEADER_TOP.dp.roundToPx()) }
            .size(GRID_W.dp, 38.dp)
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * -10.dp.toPx()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        BasicText(
            formatTime(context, now),
            Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
            style = TimeStyle,
        )
        Canvas(Modifier.align(Alignment.CenterEnd).size(38.dp)) {
            drawCircle(Color.White.copy(alpha = 0.95f))
            inset(size.minDimension * 0.23f) { drawGlyph(Glyph.Toggles, Palette.OnFocus) }
        }
    }
}

/**
 * One tile: Liquid Glass (the blurred screen behind it, or a clear tint, with a sheen and a lit
 * rim), dropping into place when the control center opens and turning white in focus.
 */
@Composable
private fun TileView(
    tile: Tile,
    focused: Boolean,
    shown: Boolean,
    delayMs: Long,
    frost: ImageBitmap?,
    content: @Composable BoxScope.(ink: Color) -> Unit,
) {
    val rect = rectOf(tile)
    val screen = LocalScreenPx.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(delayMs)
            appear.animateTo(1f, Rise)
        } else {
            appear.animateTo(0f, tween(130, easing = FastOutLinearInEasing))
        }
    }
    val focusAnim by animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "ccFocus")
    val f = focusAnim.coerceIn(0f, 1f)
    // Smaller tiles grow more, so they all lift about as far.
    val grow = when {
        tile.round -> 0.12f
        tile.h == 1 -> 0.07f
        else -> 0.05f
    }
    Box(
        Modifier
            .offset { IntOffset(rect.left.dp.roundToPx(), rect.top.dp.roundToPx()) }
            .size(rect.width.dp, rect.height.dp)
            .graphicsLayer {
                val p = appear.value
                alpha = p.coerceIn(0f, 1f)
                val s = (0.9f + 0.1f * p) * (1f + grow * focusAnim)
                scaleX = s
                scaleY = s
                translationY = (1f - p) * -14.dp.toPx()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawWithCache {
                val radius = CornerRadius(if (tile.h == 1) size.height / 2f else SQUARE_RADIUS.dp.toPx())
                // The blurred screen under this tile: the frost covers the whole screen.
                val body = frost?.let { glassOver(it, screen, Offset(rect.left.dp.toPx(), rect.top.dp.toPx())) }
                val rim = glassRim(size)
                val rimWidth = GlassRimWidth.toPx()
                onDrawBehind {
                    drawRoundRect(body ?: SolidColor(SmokedGlass), cornerRadius = radius)
                    drawRoundRect(GlassLight, cornerRadius = radius)
                    drawRoundRect(GlassSheen, cornerRadius = radius)
                    val lit = focusAnim.coerceIn(0f, 1f)
                    if (lit > 0.002f) drawRoundRect(Color.White.copy(alpha = 0.95f * lit), cornerRadius = radius)
                    drawRoundRect(rim, cornerRadius = radius, style = Stroke(rimWidth))
                }
            },
        contentAlignment = Alignment.Center,
    ) { content(lerp(Color.White, Palette.OnFocus, f)) }
}

/**
 * A capsule: its symbol on a disc (in colour while it is on), or [icon]'s app icon, a title and what
 * it is set to. Text too long for it (a long app name, "Кабелска ТВ" in SF Pro) gets smaller first.
 */
@Composable
private fun Capsule(draw: DrawScope.(Color) -> Unit, disc: Color?, title: String, subtitle: String, ink: Color, icon: String? = null) =
    Capsule(title, subtitle, ink) { GlyphDisc(draw, disc, ink, icon) }

@Composable
private fun Capsule(title: String, subtitle: String, ink: Color, symbol: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        symbol()
        Spacer(Modifier.width(9.dp))
        Column(verticalArrangement = Arrangement.Center) {
            BasicText(
                title,
                style = TitleStyle.copy(color = ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = TitleStyle.fontSize, stepSize = 0.5.sp),
            )
            BasicText(
                subtitle,
                style = SubtitleStyle.copy(color = ink.copy(alpha = 0.62f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = SubtitleStyle.fontSize, stepSize = 0.5.sp),
            )
        }
    }
}

@Composable
private fun GlyphDisc(draw: DrawScope.(Color) -> Unit, disc: Color?, ink: Color, icon: String? = null) {
    val density = LocalDensity.current.density
    val image by produceState<ImageBitmap?>(null, icon) {
        value = icon?.let { GlassApp.instance.recents.icon(it, (38f * density).roundToInt()) }
    }
    Canvas(Modifier.size(38.dp)) {
        val picture = image
        if (picture != null) {
            drawImage(picture, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
            drawCircle(Color.White.copy(alpha = 0.22f), radius = size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            return@Canvas
        }
        if (disc != null) drawCircle(disc) else drawCircle(ink.copy(alpha = 0.16f))
        inset(size.minDimension * 0.24f) { draw(if (disc != null) Color.White else ink) }
    }
}

/**
 * The light's colour: a colour wheel round the colour it is set to, as in the Home app; OK makes Left
 * and Right step through the colours, the strip following each at once.
 */
@Composable
private fun ColorCapsule(ambient: AmbientLight, adjusting: Boolean, ink: Color) {
    val strings = LocalStrings.current
    val name = strings.lightColors.getOrElse(ambient.color) { strings.lightColors[0] }
    val swatch by animateColorAsState(ambient.swatch(ambient.color), tween(180), label = "ccSwatch")
    Capsule(strings.lightColor, if (adjusting) "‹  $name  ›" else name, ink) {
        Canvas(Modifier.size(38.dp)) {
            val r = size.minDimension / 2f
            drawCircle(Hues, r)
            // A seam between the wheel and the colour, so a pale one still stands out.
            drawCircle(Color.Black.copy(alpha = 0.28f), r * 0.68f)
            drawCircle(swatch, r * 0.6f)
        }
    }
}

private val Hues = Brush.sweepGradient(
    listOf(Color(0xFFFF453A), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF63E6E2), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF375F), Color(0xFFFF453A)),
)

/** The app used before the one in front, as a round button: its own icon, or the back arrow while there is none. */
@Composable
private fun AppButton(app: AppEntry?, ink: Color) {
    val density = LocalDensity.current.density
    val image by produceState<ImageBitmap?>(null, app?.packageName) {
        value = app?.packageName?.let { GlassApp.instance.recents.icon(it, (40f * density).roundToInt()) }
    }
    val picture = image
    if (picture == null) {
        GlyphIcon(Glyph.Back, Modifier.size(25.dp)) { ink }
        return
    }
    Canvas(Modifier.size(40.dp)) {
        drawImage(picture, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
        drawCircle(Color.White.copy(alpha = 0.22f), radius = size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

/** Sound: the volume; OK makes Left and Right change it, with a level bar in place of the figure. */
@Composable
private fun SoundCapsule(volume: Volume, adjusting: Boolean, ink: Color) {
    val strings = LocalStrings.current
    val level by animateFloatAsState(volume.level, Motion.Highlight, label = "ccVolume")
    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphDisc({ drawGlyph(if (volume.muted) Glyph.SpeakerOff else Glyph.Speaker, it) }, disc = null, ink = ink)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            BasicText(
                if (adjusting) "${strings.sound}  ${volume.percent}" else strings.sound,
                style = TitleStyle.copy(color = ink),
                maxLines = 1,
            )
            if (adjusting) {
                Canvas(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp)) {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(ink.copy(alpha = 0.2f), cornerRadius = r)
                    drawRoundRect(ink, size = Size(size.width * level.coerceIn(0f, 1f), size.height), cornerRadius = r)
                }
            } else {
                BasicText(
                    if (volume.muted) strings.muted else "${strings.volume} ${volume.percent}",
                    style = SubtitleStyle.copy(color = ink.copy(alpha = 0.62f)),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The TV's performance, live: how fast the internet answers (a ring that lights green, yellow or
 * red) and the memory in use as rings, what the network brings in and the memory in GB below them.
 * (The processor's load is closed to apps on the TCL.)
 */
@Composable
private fun Performance(stats: Stats, ink: Color) {
    val strings = LocalStrings.current
    Column(Modifier.fillMaxSize().padding(start = 13.dp, end = 13.dp, top = 11.dp, bottom = 11.dp)) {
        BasicText(strings.performance, style = SmallStyle.copy(color = ink.copy(alpha = 0.7f)), maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val ping = stats.pingMs?.takeIf { it != SystemStats.NO_ANSWER }
            Gauge(
                value = if (ping != null) 1f else null,
                color = when {
                    ping == null -> PingBad
                    ping < 60 -> PingGood
                    ping < 150 -> PingSlow
                    else -> PingBad
                },
                figure = ping?.toString() ?: "–",
                label = "PING ms",
                ink = ink,
            )
            Gauge(stats.ram, RamColor, "${(stats.ram * 100).roundToInt()}%", "RAM", ink)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            BasicText(
                stats.downMbps?.let { "↓ ${figure(it)} Mb/s" } ?: "↓ –",
                Modifier.weight(1f),
                style = SmallStyle.copy(color = ink),
                maxLines = 1,
            )
            BasicText(
                "%.1f/%.1f GB".format(stats.ramUsedGb, stats.ramTotalGb),
                style = SmallStyle.copy(color = ink, textAlign = TextAlign.End),
                maxLines = 1,
            )
        }
    }
}

/** Megabits a second, to a tenth below 10. */
private fun figure(mbps: Float) = if (mbps >= 9.95f) "${mbps.roundToInt()}" else "%.1f".format(mbps)

@Composable
private fun Gauge(value: Float?, color: Color, figure: String, label: String, ink: Color) {
    val shown by animateFloatAsState(value ?: 0f, tween(700, easing = Motion.EaseOut), label = "ccGauge")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 5.dp.toPx()
                val arc = Size(size.width - w, size.height - w)
                val tl = Offset(w / 2f, w / 2f)
                drawArc(ink.copy(alpha = 0.18f), 0f, 360f, false, tl, arc, style = Stroke(w))
                if (value != null) drawArc(color, -90f, 360f * shown.coerceIn(0f, 1f), false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            BasicText(figure, style = FigureStyle.copy(color = ink), maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        BasicText(label, style = TinyStyle.copy(color = ink.copy(alpha = 0.7f)), maxLines = 1)
    }
}

private val TimeStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Color.White, fontFeatureSettings = "tnum")
private val LabelStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 16.sp)
private val TitleStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
private val SubtitleStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 12.sp)
private val SmallStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, fontFeatureSettings = "tnum")
private val FigureStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, fontFeatureSettings = "tnum")
private val TinyStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 0.6.sp)
private val CaptionStyle = TextStyle(
    fontFamily = TextFont,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    color = Color.White.copy(alpha = 0.8f),
    textAlign = TextAlign.Center,
)
