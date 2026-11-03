package com.andrej.glasstv.quicksettings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.controlcenter.GlassLight
import com.andrej.glasstv.controlcenter.GlassRimWidth
import com.andrej.glasstv.controlcenter.GlassSheen
import com.andrej.glasstv.controlcenter.SmokedGlass
import com.andrej.glasstv.controlcenter.glassOver
import com.andrej.glasstv.controlcenter.glassRim
import com.andrej.glasstv.remote.TvSound
import com.andrej.glasstv.ui.DisplayFont
import com.andrej.glasstv.ui.Glyph
import com.andrej.glasstv.ui.GlyphIcon
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.Palette
import com.andrej.glasstv.ui.Strings
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.damageBounds
import com.andrej.glasstv.ui.drawGlyph
import com.andrej.glasstv.ui.drawWifi
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// The layout, in design units (960x540), measured off tvOS 26's player (a 1920x1080 frame of Apple's
// own video of it, halved): the title at the left, the round buttons at the right on the same line,
// the bar across the screen under them, its figures under it, and the tabs along the bottom. The
// quick settings have two bars, the backlight's over the volume's; the title and the buttons rise by
// a bar's room while there are two.
private const val LEFT = 41f
private const val RIGHT = 919f
private const val TITLE_TOP = 402f
/** The title's room: up to the round buttons. */
private const val TITLE_W = 540f
private const val BUTTON = 35f
private const val BUTTON_PITCH = 47f
private const val BUTTON_CY = 417.5f
/** The middle of the bar nearest the tabs (the volume's), and the room each bar takes above it. */
private const val BAR_CY = 453.5f
private const val BAR_PITCH = 36f
private const val BAR_H = 6f
/** A bar in focus, and its playhead: a thin line through it, longer than it is thick. */
private const val BAR_FOCUSED_H = 9.5f
private const val PLAYHEAD_W = 1.6f
private const val PLAYHEAD_H = 16f
/** A bar's figures, under its middle, and the least room between the figure and the bar's name. */
private const val FIGURES_BELOW = 7.5f
private const val FIGURE_GAP = 12f
private const val PILL_TOP = 482.5f
private const val PILL_H = 32f
private const val PILL_GAP = 12f
private const val PILL_PAD = 14f
/** Between a tab's name and its value. */
private const val VALUE_GAP = 7f

/** Room around a glass button to grow into in focus (a choice grows 8% of 216, 9 units a side). */
private const val GROW_ROOM = 12f

/** The room for a round button's name over it. */
private const val NAME_W = 120f

// A tab's choices rise above it as tvOS 26's Control Center lists its sleep timer's: capsules
// 216 x 39, 48.5 apart, the one in focus white and 8% bigger.
private const val ROW_W = 216f
private const val ROW_H = 39f
private const val ROW_PITCH = 48.5f
private const val LIST_BOTTOM = PILL_TOP - 14f

/** The most choices a tab has (the sound modes, 7 with Intelligent). */
private val LONGEST_LIST = maxOf(PictureMode.entries.size, TvSound.MODES.size, SleepTimer.CHOICES.size)

/**
 * The top of the window: room for the longest list and its focus growing. Sized for the picture
 * presets alone, the sound modes' top row (Intelligent) was cut off by the window's edge.
 */
private val TOP = LIST_BOTTOM - (LONGEST_LIST - 1) * ROW_PITCH - ROW_H - 10f

/** How high the quick settings reach, from the bottom (0..1 of the screen): nothing above needs a window. */
val QUICK_REACH = (540f - TOP) / 540f

/** How dark the shade is at the bottom edge. */
const val QUICK_SHADE = 0.86f

/** Where the shade starts, in design units: above it the picture is left as it is. */
private const val SHADE_TOP = 240f

/** Where the frost on the home screen is whole: above the title, so nothing of the dock reads through the controls. */
private const val FROST_FULL = 372f

/**
 * How deep the frost under the controls is on the home screen (0..1) at (u, v), 0..1 across and down
 * the screen: none above the shade, whole from just above the title down, easing in between.
 */
fun quickShadeAt(@Suppress("UNUSED_PARAMETER") u: Float, v: Float): Float {
    val t = ((v * 540f - SHADE_TOP) / (FROST_FULL - SHADE_TOP)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The plain shade, for where nothing can be blurred (over other apps). Drawn on the 540-unit-high screen. */
fun DrawScope.drawQuickShade(alpha: Float) {
    if (alpha <= 0.002f) return
    val k = size.height / 540f
    val brush = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0f),
        0.3f to Color.Black.copy(alpha = 0.32f * alpha),
        0.6f to Color.Black.copy(alpha = 0.76f * alpha),
        1f to Color.Black.copy(alpha = alpha),
        startY = SHADE_TOP * k,
        endY = 540f * k,
    )
    drawRect(brush, topLeft = Offset(0f, SHADE_TOP * k), size = Size(size.width, (540f - SHADE_TOP) * k))
}

/**
 * Draws the part of [image] (the home screen frosted, shaped by [quickShadeAt]) that the shade
 * reaches, over the same part of this screen-sized area: the TV's GPU pays for every pixel blended,
 * so the top is skipped.
 */
fun DrawScope.drawQuickShadeOf(image: ImageBitmap, alpha: Float) {
    if (alpha <= 0.002f) return
    val srcY = (image.height * SHADE_TOP / 540f).toInt()
    val dstY = (size.height * srcY / image.height).roundToInt()
    drawImage(
        image,
        srcOffset = IntOffset(0, srcY),
        srcSize = IntSize(image.width, image.height - srcY),
        dstOffset = IntOffset(0, dstY),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt() - dstY),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** Each choice rises into place with a soft settle, as the control center's tiles drop into theirs. */
private val Rise = spring<Float>(dampingRatio = 0.76f, stiffness = 380f)
private const val STAGGER_MS = 22L

/**
 * The quick settings of the TCL remote's button right of 0, in place of TCL's own bar: tvOS 26's
 * player controls over the picture. "Quick Settings" at the left (where tvOS names the show), the
 * round buttons at the right (the screens TCL's bar opens), the backlight and the volume as the
 * player's bar, and the tabs along the bottom (the picture preset, the sound mode, the sleep timer,
 * the switches), the picture's and the timer's choices rising above them in Liquid Glass capsules.
 * What is in focus turns white with dark ink.
 *
 * Laid out on the whole screen's 540 units, bottom-aligned in its window.
 */
@Composable
fun BoxScope.QuickSettingsPanel(model: QuickSettingsModel, shown: Boolean) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) appear.animateTo(1f, Motion.Panel) else appear.animateTo(0f, tween(160, easing = FastOutLinearInEasing))
    }
    // An open list takes the title, the buttons and the bar away, as the sleep timer's does the
    // control center's tiles.
    val away by animateFloatAsState(if (model.list != null) 1f else 0f, tween(200, easing = Motion.EaseOut), label = "qsAway")
    // Where each tab starts, in px from the left, for its list to rise from.
    val tabs = remember { mutableStateMapOf<QuickPill, Int>() }
    // The volume as the TV has it, also when the remote's own volume buttons change it meanwhile.
    LaunchedEffect(shown) {
        while (shown) {
            model.volume.refresh()
            delay(500)
        }
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val p = appear.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 18.dp.toPx()
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        val behind = Modifier.graphicsLayer {
            alpha = 1f - away
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        val strings = LocalStrings.current
        val lift = if (model.hasBrightness) BAR_PITCH else 0f
        fun focused(row: QuickRow) = model.list == null && model.row == row
        Title(lift, behind)
        QuickButton.entries.forEach { button ->
            key(button) { RoundButton(button, lift, focused = focused(QuickRow.Buttons) && model.button == button, behind) }
        }
        if (model.hasBrightness) {
            LevelBar(model.brightness, strings.brightness, BAR_CY - BAR_PITCH, focused(QuickRow.Brightness), behind)
        }
        val volume = model.volume
        LevelBar(volume.percent, if (volume.muted) strings.muted else strings.sound, BAR_CY, focused(QuickRow.Volume), behind)
        Tabs(model, shown, tabs)
        Choices(model, tabs)
    }
}

/** "Quick Settings", where tvOS sets the show's name. */
@Composable
private fun Title(lift: Float, modifier: Modifier) {
    val strings = LocalStrings.current
    BasicText(
        strings.quickSettings,
        modifier.offset { IntOffset(LEFT.dp.roundToPx(), (TITLE_TOP - lift).dp.roundToPx()) }.width(TITLE_W.dp),
        style = TitleStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RoundButton(button: QuickButton, lift: Float, focused: Boolean, modifier: Modifier) {
    val strings = LocalStrings.current
    val cx = RIGHT - BUTTON / 2f - (QuickButton.entries.size - 1 - button.ordinal) * BUTTON_PITCH
    val focus = animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "qsButton")
    LitGlass(
        focus,
        grow = 0.12f,
        at = Offset(cx - BUTTON / 2f, BUTTON_CY - lift - BUTTON / 2f),
        modifier.offset { IntOffset((cx - BUTTON / 2f).dp.roundToPx(), (BUTTON_CY - lift - BUTTON / 2f).dp.roundToPx()) }.size(BUTTON.dp),
    ) { ink ->
        Canvas(Modifier.align(Alignment.Center).size(if (button == QuickButton.Settings) 18.dp else 17.dp)) {
            when (button) {
                QuickButton.Speakers -> drawGlyph(Glyph.HifiSpeaker, ink)
                QuickButton.Network -> drawWifi(online = true, bars = 3, color = ink)
                QuickButton.PictureSettings -> drawGlyph(Glyph.Screensaver, ink)
                QuickButton.DisplaySound -> drawGlyph(Glyph.Television, ink)
                QuickButton.Settings -> drawGlyph(Glyph.Gear, ink)
            }
        }
    }
    // Its name over it while it is in focus: there are as many as TCL's bar has, and their symbols
    // alone don't say what each opens. Centred over it, the last ones kept inside the right margin.
    val name = when (button) {
        QuickButton.Speakers -> strings.speakers
        QuickButton.Network -> strings.network
        QuickButton.PictureSettings -> strings.pictureSettings
        QuickButton.DisplaySound -> strings.displaySound
        QuickButton.Settings -> strings.systemSettings
    }
    val nameLeft = (cx - NAME_W / 2f).coerceAtMost(RIGHT - NAME_W)
    BasicText(
        name,
        modifier
            .offset { IntOffset(nameLeft.dp.roundToPx(), (BUTTON_CY - lift - BUTTON / 2f - 22f).dp.roundToPx()) }
            .width(NAME_W.dp)
            .damageBounds(0.dp, 0.dp, 0.dp, 0.dp)
            .graphicsLayer {
                alpha = focus.value.coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        style = CaptionStyle.copy(textAlign = if (nameLeft < cx - NAME_W / 2f) TextAlign.End else TextAlign.Center),
        maxLines = 1,
    )
}

/**
 * The backlight or the volume as tvOS's playback bar: filled up to [value] (0..100; null while it
 * isn't known), the playhead's thin line through it and the figure under it, [label] at the right end
 * where the time left is. In focus the bar thickens, and Left and Right move it.
 */
@Composable
private fun LevelBar(value: Int?, label: String, cy: Float, focused: Boolean, modifier: Modifier) {
    val level = animateFloatAsState((value ?: 0) / 100f, Motion.Highlight, label = "qsLevel")
    val focus = animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "qsBar")
    val width = RIGHT - LEFT
    // Layers that only scale and slide, in bounds of their own: a step or a change of focus redraws
    // just the bar. The bar is drawn at its focused thickness and squeezed out of focus; the fill slides
    // in from the left inside it, as far as the level, and the playhead with it.
    Box(
        modifier
            .offset { IntOffset(LEFT.dp.roundToPx(), (cy - PLAYHEAD_H / 2f).dp.roundToPx()) }
            .size(width.dp, PLAYHEAD_H.dp)
            .damageBounds(2.dp, 0.dp, 2.dp, 0.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(BAR_FOCUSED_H.dp)
                .graphicsLayer {
                    scaleY = (BAR_H + (BAR_FOCUSED_H - BAR_H) * focus.value) / BAR_FOCUSED_H
                    shape = RoundedCornerShape(50)
                    clip = true
                }
                .drawBehind {
                    drawRect(SmokedGlass.copy(alpha = 0.55f))
                    drawRect(Color.White.copy(alpha = 0.2f))
                },
        ) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = focus.value.coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .background(Color.White.copy(alpha = 0.14f)),
            )
            Spacer(Modifier.matchParentSize().graphicsLayer { translationX = (level.value.coerceIn(0f, 1f) - 1f) * size.width }.background(Color.White.copy(alpha = 0.92f)))
        }
        Spacer(
            Modifier
                .align(Alignment.CenterStart)
                .size(PLAYHEAD_W.dp, PLAYHEAD_H.dp)
                .graphicsLayer {
                    translationX = level.value.coerceIn(0f, 1f) * width.dp.toPx() - size.width / 2f
                    scaleY = 0.62f + 0.38f * focus.value
                }
                .background(Color.White, RoundedCornerShape(50)),
        )
    }
    // The figure follows the playhead, short of the label at the right end, which always shows which
    // bar this is. It is placed once and slides as a layer: placed where the level is, it was laid out
    // again, and the whole window redrawn, on every frame of the bar's glide.
    Layout(
        content = {
            BasicText(value?.toString() ?: "–", Modifier.graphicsLayer(), style = FigureStyle, maxLines = 1)
            BasicText(label, style = FigureStyle, maxLines = 1)
        },
        modifier = modifier.offset { IntOffset(LEFT.dp.roundToPx(), (cy + FIGURES_BELOW).dp.roundToPx()) }.width(width.dp).damageBounds(0.dp, 0.dp, 0.dp, 0.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val figure = measurables[0].measure(loose)
        val name = measurables[1].measure(loose)
        layout(constraints.maxWidth, maxOf(figure.height, name.height)) {
            name.place(constraints.maxWidth - name.width, 0)
            val room = (constraints.maxWidth - name.width - FIGURE_GAP.dp.roundToPx() - figure.width).coerceAtLeast(0)
            val width = constraints.maxWidth
            figure.placeWithLayer(0, 0) {
                translationX = (width * level.value - figure.width / 2f).roundToInt().coerceIn(0, room).toFloat()
            }
        }
    }
}

/**
 * The tabs along the bottom, each with what it is set to beside its name (a switch On or Off),
 * filling the row from margin to margin: the room left over is shared out among them, and text too
 * long for the row gets smaller, all of it alike.
 */
@Composable
private fun Tabs(model: QuickSettingsModel, shown: Boolean, tabs: SnapshotStateMap<QuickPill, Int>) {
    val strings = LocalStrings.current
    // The sleep timer counts down while the quick settings are up.
    val endsAt = model.timer.endsAt
    val left by produceState(0L, endsAt, shown) {
        while (shown && endsAt != null) {
            value = model.timer.remaining()
            delay(250)
        }
    }
    fun onOff(on: Boolean?) = on?.let { if (it) strings.on else strings.off }
    val texts = QuickPill.entries.map { pill ->
        val label = when (pill) {
            QuickPill.Picture -> strings.picture
            QuickPill.Sound -> strings.sound
            QuickPill.SleepTimer -> strings.sleepTimer
            QuickPill.CloseApp -> strings.closeApp
            QuickPill.Bluetooth -> "Bluetooth"
            QuickPill.Cec -> strings.cec
        }
        val value = when (pill) {
            QuickPill.Picture -> pictureName(model.mode, strings)
            QuickPill.Sound -> model.toggles.soundMode?.let { strings.soundModes.getOrNull(it)?.takeIf(String::isNotEmpty) }
            QuickPill.SleepTimer -> if (endsAt != null) countdown(left) else strings.sleepChoices.last()
            QuickPill.CloseApp -> model.openApp?.label
            QuickPill.Bluetooth -> onOff(model.toggles.bluetooth)
            QuickPill.Cec -> onOff(model.toggles.cec)
        }
        label to value
    }
    // Each tab's text, measured at full size, in design units.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    fun widthOf(text: String, style: TextStyle) = with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp().value }
    val natural = texts.map { (label, value) -> widthOf(label, TabStyle) + (value?.let { VALUE_GAP + widthOf(it, TabValueStyle) } ?: 0f) }
    val room = RIGHT - LEFT - texts.size * 2 * PILL_PAD - (texts.size - 1) * PILL_GAP
    val scale = (room / natural.sum()).coerceAtMost(1f)
    val share = ((room - natural.sum() * scale) / texts.size).coerceAtLeast(0f)

    val widths = natural.map { it * scale + 2 * PILL_PAD + share }
    val lefts = widths.runningFold(LEFT) { x, w -> x + w + PILL_GAP }

    // Where each tab starts, in px, as the row places them, for its list to rise from: worked out
    // here, not read off the tabs as they are placed. A tab that reads where it is gets laid out
    // again whenever a layer of its own moves, and Compose then redraws the whole window: the focus
    // growing a tab did that on every frame of every move.
    SideEffect {
        with(density) {
            var x = LEFT.dp.roundToPx()
            QuickPill.entries.forEachIndexed { i, pill ->
                tabs[pill] = x
                x += widths[i].dp.roundToPx() + PILL_GAP.dp.roundToPx()
            }
        }
    }
    Row(
        Modifier.offset { IntOffset(LEFT.dp.roundToPx(), PILL_TOP.dp.roundToPx()) }.height(PILL_H.dp),
        horizontalArrangement = Arrangement.spacedBy(PILL_GAP.dp),
    ) {
        QuickPill.entries.forEachIndexed { i, pill ->
            key(pill) {
                Tab(
                    label = texts[i].first,
                    value = texts[i].second,
                    scale = scale,
                    width = widths[i],
                    left = lefts[i],
                    focused = model.list == null && model.row == QuickRow.Pills && model.pill == pill,
                    opened = model.list == pill,
                    // Close with nothing to quit (the home screen, an input) is there, but faint.
                    enabled = pill != QuickPill.CloseApp || model.openApp != null,
                )
            }
        }
    }
}

/** A tab [width] units wide from [left], its text at [scale] of its size and centred in it; faint unless [enabled]. */
@Composable
private fun Tab(label: String, value: String?, scale: Float, width: Float, left: Float, focused: Boolean, opened: Boolean, enabled: Boolean) {
    val focus = animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "qsTab")
    // The tab whose list is open stays a little lit under it.
    val open = animateFloatAsState(if (opened) 1f else 0f, tween(180), label = "qsTabOpen")
    LitGlass(
        focus,
        grow = 0.06f,
        at = Offset(left, PILL_TOP),
        Modifier.width(width.dp).fillMaxHeight(),
        lit = { focus.value + 0.2f * open.value * (1f - focus.value) },
    ) { full ->
        val ink = if (enabled) full else full.copy(alpha = full.alpha * 0.4f)
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = TabStyle.copy(color = ink, fontSize = TabStyle.fontSize * scale), maxLines = 1)
            if (value != null) {
                Spacer(Modifier.width((VALUE_GAP * scale).dp))
                BasicText(value, style = TabValueStyle.copy(color = ink.copy(alpha = 0.62f), fontSize = TabValueStyle.fontSize * scale), maxLines = 1)
            }
        }
    }
}

/** The open tab's choices, rising above it from the tab up; they sink away when it closes. */
@Composable
private fun Choices(model: QuickSettingsModel, tabs: Map<QuickPill, Int>) {
    val strings = LocalStrings.current
    // The list on the screen: the open one, or the last one while it goes.
    val last = remember { arrayOf<QuickPill?>(null) }
    model.list?.let { last[0] = it }
    val pill = last[0] ?: return
    val left = tabs[pill] ?: return
    val names = when (pill) {
        QuickPill.Picture -> strings.pictureModes
        QuickPill.Sound -> TvSound.MODES.map { strings.soundModes[it] }
        QuickPill.SleepTimer -> strings.sleepChoices
        else -> emptyList()
    }
    val count = model.choices(pill)
    val chosen = model.chosen(pill)
    val open = model.list == pill
    for (i in 0 until count) {
        key(pill, i) {
            // From the tab up: the lowest first.
            val fromTab = count - 1 - i
            ChoiceRow(
                name = names.getOrElse(i) { "" },
                chosen = i == chosen,
                focused = open && model.option == i,
                shown = open,
                delayMs = 30L + fromTab * STAGGER_MS,
                x = left,
                top = LIST_BOTTOM - ROW_H - fromTab * ROW_PITCH,
            )
        }
    }
}

@Composable
private fun ChoiceRow(name: String, chosen: Boolean, focused: Boolean, shown: Boolean, delayMs: Long, x: Int, top: Float) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(delayMs)
            appear.animateTo(1f, Rise)
        } else {
            appear.animateTo(0f, tween(120, easing = FastOutLinearInEasing))
        }
    }
    val focus = animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "qsChoice")
    val left = with(LocalDensity.current) { x.toDp().value }
    Box(
        Modifier
            .offset { IntOffset(x, top.dp.roundToPx()) }
            .size(ROW_W.dp, ROW_H.dp)
            .damageBounds(GROW_ROOM.dp, GROW_ROOM.dp, GROW_ROOM.dp, GROW_ROOM.dp)
            .graphicsLayer {
                val p = appear.value
                alpha = p.coerceIn(0f, 1f)
                val s = 0.9f + 0.1f * p
                scaleX = s
                scaleY = s
                translationY = (1f - p) * 12.dp.toPx()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        LitGlass(focus, grow = 0.083f, at = Offset(left, top), Modifier.matchParentSize()) { ink ->
            if (chosen) {
                GlyphIcon(Glyph.Checkmark, Modifier.align(Alignment.CenterStart).padding(start = 13.dp).size(15.dp)) { ink }
            }
            BasicText(
                name,
                Modifier.align(Alignment.CenterStart).padding(start = 40.dp, end = 14.dp),
                style = ChoiceStyle.copy(color = ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The control center's Liquid Glass (see [glassOver]): over the home screen the home screen behind it,
 * blurred ([QuickBackdrop]); over other apps, where nothing can be read back (an HDMI picture least of
 * all), smoked. A little light, a sheen and a lit rim on both. It turns white with dark ink in focus
 * ([lit], [focus]). Capsules and circles; [at] is where one rests, in design units, for the picture
 * it shows.
 *
 * The glass is drawn once and kept: the focus only scales it and fades layers in it (the white, and
 * the content in white ink over the same in dark ink), inside bounds of its own, so a move redraws just
 * the glass it leaves and the glass it reaches. Drawn from the focus, the TV's GPU redrew the whole
 * window on every frame of every move, and over an HDMI picture a quarter of the frames came late.
 * The picture is sampled where the glass rests, so rising into place or growing redraws nothing.
 */
@Composable
private fun LitGlass(
    focus: State<Float>,
    grow: Float,
    at: Offset,
    modifier: Modifier,
    lit: () -> Float = { focus.value },
    content: @Composable BoxScope.(ink: Color) -> Unit,
) {
    Box(
        modifier
            .damageBounds(GROW_ROOM.dp, GROW_ROOM.dp, GROW_ROOM.dp, GROW_ROOM.dp)
            .graphicsLayer {
                val s = 1f + grow * focus.value
                scaleX = s
                scaleY = s
            }
            .drawWithCache {
                val radius = CornerRadius(size.height / 2f)
                // Read here, so the glass is drawn again when the home screen's picture comes or goes.
                val body = QuickBackdrop.image?.let {
                    glassOver(it, Size(960.dp.toPx(), 540.dp.toPx()), Offset(at.x.dp.toPx(), at.y.dp.toPx()))
                }
                onDrawBehind {
                    drawRoundRect(body ?: SolidColor(SmokedGlass), cornerRadius = radius)
                    drawRoundRect(GlassLight, cornerRadius = radius)
                    drawRoundRect(GlassSheen, cornerRadius = radius)
                }
            },
    ) {
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = lit().coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawBehind { drawRoundRect(Color.White.copy(alpha = 0.95f), cornerRadius = CornerRadius(size.height / 2f)) },
        )
        Spacer(
            Modifier.matchParentSize().drawWithCache {
                val rim = glassRim(size)
                val stroke = Stroke(GlassRimWidth.toPx())
                onDrawBehind { drawRoundRect(rim, cornerRadius = CornerRadius(size.height / 2f), style = stroke) }
            },
        )
        Box(Modifier.matchParentSize().inkLayer { 1f - focus.value }) { content(Color.White) }
        Box(Modifier.matchParentSize().inkLayer { focus.value }) { content(Palette.OnFocus) }
    }
}

/**
 * The home screen behind the quick settings, blurred whole, while they are up over it: the home
 * screen sets it once it has made it, and clears it after they have gone. Null over other apps.
 */
object QuickBackdrop {
    var image by mutableStateOf<ImageBitmap?>(null)
}

private fun Modifier.inkLayer(alpha: () -> Float) = graphicsLayer {
    this.alpha = alpha().coerceIn(0f, 1f)
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** The preset's name; for presets outside the SDR list, what kind of picture it is for. */
private fun pictureName(mode: Int?, strings: Strings): String? {
    PictureMode.of(mode)?.let { return strings.pictureModes[it.ordinal] }
    return when (mode) {
        null -> null
        in 5..7 -> strings.game
        8 -> "PC"
        else -> "HDR"
    }
}

/** What is left of the sleep timer: 29:41, or 1:59:41. */
private fun countdown(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private val CaptionStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
private val TitleStyle = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.3).sp, color = Color.White)
private val FigureStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White, fontFeatureSettings = "tnum")
private val TabStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
private val TabValueStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, fontFeatureSettings = "tnum")
private val ChoiceStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 14.sp)
