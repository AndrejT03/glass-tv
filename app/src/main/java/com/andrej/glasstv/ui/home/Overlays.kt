package com.andrej.glasstv.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.ui.Glyph
import com.andrej.glasstv.ui.GlyphIcon
import com.andrej.glasstv.ui.Link
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.Palette
import com.andrej.glasstv.ui.Strings
import com.andrej.glasstv.ui.Type
import com.andrej.glasstv.ui.drawEthernet
import com.andrej.glasstv.ui.drawWifi
import com.andrej.glasstv.ui.formatTime
import com.andrej.glasstv.ui.glass.LocalBackdrop
import com.andrej.glasstv.ui.glass.LocalBackdropTransition
import com.andrej.glasstv.ui.glass.liquidGlass
import com.andrej.glasstv.ui.rememberMinute
import com.andrej.glasstv.ui.rememberNetwork
import kotlin.math.min

private val ShadowColor = Color.Black.copy(alpha = 0.45f)
private val SoftShadow = Color.Black.copy(alpha = 0.25f)

/** tvOS menus and sheets use the darker glass variant so text stays readable on any wallpaper. */
private val DarkGlass = Color(0xFF0B0B12).copy(alpha = 0.42f)

/**
 * Fades an AnimatedVisibility's content in and out without the offscreen buffer a layer's alpha
 * needs (AnimatedVisibility's own fadeIn uses one): each draw fades on its own instead. On the TV's
 * tile-based GPU every offscreen buffer flushes and reloads the whole screen, and these fades were
 * the costliest frames of all.
 */
@Composable
private fun AnimatedVisibilityScope.fade(inMs: Int, outMs: Int = inMs): Modifier {
    val alpha by transition.animateFloat(
        transitionSpec = { tween(if (targetState == EnterExitState.Visible) inMs else outMs) },
        label = "fade",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    return Modifier.graphicsLayer {
        this.alpha = alpha
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

// region top-right status capsule

/**
 * tvOS 26 status capsule: time and the Wi-Fi signal in a glass pill at the top right. Moving up
 * into it opens it sideways into the controls (screen saver, inputs, settings).
 */
@Composable
fun StatusCapsule(c: HomeController, modifier: Modifier) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val now by rememberMinute()
    // Through derived state, so the capsule recomposes only when the focus comes into it, moves in
    // it or leaves it, not on every move along the dock.
    val focused by remember(c) { derivedStateOf { (c.focus as? Focus.Control)?.index ?: -1 } }
    val open = focused >= 0
    val transition = LocalBackdropTransition.current
    val fallback = if (transition == null) LocalBackdrop.current else null
    val light by remember(transition, fallback) {
        derivedStateOf { (transition?.current ?: fallback)?.isBright(0.72f, 0.02f, 0.99f, 0.16f) == true }
    }
    val lightness = animateFloatAsState(if (light) 1f else 0f, tween(480), label = "capsuleLight")
    // Read only where it is drawn: read here, the colour change recomposed the capsule on each of its frames.
    val contentColor = animateColorAsState(if (light) Palette.OnFocus else Color.White, tween(480), label = "capsuleContent")
    val content = { contentColor.value }
    val timeStyle = remember { Type.Body.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum") }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(
            Modifier
                .height(40.dp)
                .shadow(8.dp, CircleShape, ambientColor = SoftShadow, spotColor = SoftShadow)
                .liquidGlass(RoundedCornerShape(20.dp), tintOf = { lerp(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.45f), lightness.value) })
                .padding(start = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                open,
                enter = expandHorizontally(spring(0.85f, 420f), expandFrom = Alignment.End),
                exit = shrinkHorizontally(tween(200), shrinkTowards = Alignment.End),
            ) {
                // AnimatedVisibility clips to its bounds: the padding gives the focused button room to
                // grow, or its circle came out flattened at the top and bottom and cut on the left.
                Row(Modifier.then(fade(160, 140)).padding(start = 2.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    c.controls.forEachIndexed { i, id -> CapsuleButton(glyphOf(id), i == focused, content) }
                    Spacer(Modifier.width(4.dp))
                }
            }
            BasicText(
                remember(now) { formatTime(context, now) },
                Modifier.padding(start = 14.dp, end = 8.dp),
                style = timeStyle,
                color = content,
            )
            NetworkIcon(Modifier.size(32.dp), content)
        }
        val label = c.controls.getOrNull(focused)?.let { labelOf(it, strings) }
        BasicText(
            label.orEmpty(),
            Modifier.padding(top = 10.dp, end = 8.dp).graphicsLayer { alpha = if (label != null) 1f else 0f },
            style = Type.Caption,
            maxLines = 1,
            color = content,
        )
    }
}

@Composable
private fun CapsuleButton(glyph: Glyph, focused: Boolean, content: () -> Color) {
    val f = animateFloatAsState(if (focused) 1f else 0f, Motion.Focus, label = "capsule")
    Box(
        Modifier
            .size(34.dp)
            .graphicsLayer {
                val s = 1f + 0.08f * f.value
                scaleX = s
                scaleY = s
            }
            .drawBehind { drawCircle(Color.White.copy(alpha = 0.94f * f.value.coerceIn(0f, 1f))) },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Modifier.size(18.dp)) { lerp(content(), Palette.OnFocus, f.value.coerceIn(0f, 1f)) }
    }
}

/** Wi-Fi with its signal bars (or an Ethernet port), following the connection as it changes. */
@Composable
private fun NetworkIcon(modifier: Modifier, color: () -> Color) {
    val network by rememberNetwork()
    Canvas(modifier.padding(6.dp)) {
        when (network.link) {
            Link.Ethernet -> drawEthernet(color())
            Link.Wifi -> drawWifi(online = true, bars = network.bars, color = color())
            Link.None -> drawWifi(online = false, bars = 0, color = color())
        }
    }
}

private fun glyphOf(id: ControlId) = when (id) {
    ControlId.Screensaver -> Glyph.Screensaver
    ControlId.Inputs -> Glyph.Television
    ControlId.Settings -> Glyph.Sliders
    ControlId.AndroidSettings -> Glyph.Gear
    ControlId.ControlCenter -> Glyph.Toggles
}

private fun labelOf(id: ControlId, s: Strings) = when (id) {
    ControlId.Screensaver -> s.screensaver
    ControlId.Inputs -> s.inputs
    ControlId.Settings -> s.settings
    ControlId.AndroidSettings -> s.systemSettings
    ControlId.ControlCenter -> s.controlCenter
}

// endregion

// region context menu

private const val MENU_W = 290f
private const val MENU_ROW = 48f
private const val MENU_PAD = 8f

/** tvOS 26 context menu: a glass sheet that grows out of the held icon. */
@Composable
fun BoxScope.AppMenu(c: HomeController) {
    val strings = LocalStrings.current
    val open = c.apps.firstOrNull { it.key == c.menuKey }
    // Plain (non-state) memory of the last app, so the menu keeps its content while animating out.
    val last = remember { arrayOfNulls<AppEntry>(1) }
    if (open != null) last[0] = open
    val app = last[0] ?: return

    val index = c.apps.indexOfFirst { it.key == app.key }.coerceAtLeast(0)
    val items = c.menuItems(app)
    val tile = Grid.tileRect(index)
    val height = MENU_PAD * 2 + MENU_ROW * items.size
    val above = tile.center.y > 270f
    val top = if (above) tile.top - 18f - height else tile.bottom + 40f
    val left = (tile.center.x - MENU_W / 2f).coerceIn(45f, 960f - 45f - MENU_W)
    val origin = TransformOrigin(((tile.center.x - left) / MENU_W).coerceIn(0f, 1f), if (above) 1f else 0f)

    AnimatedVisibility(
        open != null,
        modifier = Modifier.offset(left.dp, top.dp),
        enter = scaleIn(spring(0.72f, 460f), initialScale = 0.75f, transformOrigin = origin),
        exit = scaleOut(tween(170), targetScale = 0.85f, transformOrigin = origin),
    ) {
        Box(
            Modifier
                .then(fade(140, 170))
                .size(MENU_W.dp, height.dp)
                .shadow(14.dp, RoundedCornerShape(26.dp), ambientColor = ShadowColor, spotColor = ShadowColor),
        ) {
            // The glass in a layer of its own, as on the settings sheet: it stays as it is while the
            // menu scales in and the highlight moves.
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .liquidGlass(RoundedCornerShape(26.dp), tint = DarkGlass, restsAt = DpOffset(left.dp, top.dp)),
            )
            val hi = animateFloatAsState(c.menuIndex.toFloat(), Motion.Highlight, label = "menuHighlight")
            Box(
                Modifier
                    .offset { IntOffset(MENU_PAD.dp.roundToPx(), (MENU_PAD + hi.value * MENU_ROW).dp.roundToPx()) }
                    .size((MENU_W - MENU_PAD * 2).dp, MENU_ROW.dp)
                    .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(20.dp)),
            )
            items.forEachIndexed { i, item ->
                val focused = i == c.menuIndex
                val destructive = item == MenuAction.Uninstall
                val color by animateColorAsState(
                    when {
                        destructive -> Palette.Destructive
                        focused -> Palette.OnFocus
                        else -> Color.White
                    },
                    tween(140),
                    label = "menuText",
                )
                Row(
                    Modifier
                        .offset(MENU_PAD.dp, (MENU_PAD + i * MENU_ROW).dp)
                        .size((MENU_W - MENU_PAD * 2).dp, MENU_ROW.dp)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(menuLabel(item, strings), Modifier.weight(1f), style = Type.Body.copy(color = color), maxLines = 1)
                    GlyphIcon(menuGlyph(item), Modifier.size(19.dp)) { color }
                }
            }
        }
    }
}

private fun menuLabel(a: MenuAction, s: Strings) = when (a) {
    MenuAction.Move -> s.move
    MenuAction.Info -> s.info
    MenuAction.Uninstall -> s.uninstall
}

private fun menuGlyph(a: MenuAction) = when (a) {
    MenuAction.Move -> Glyph.Move
    MenuAction.Info -> Glyph.Info
    MenuAction.Uninstall -> Glyph.Trash
}

// endregion

// region settings panel

private const val PANEL_W = 420f
private const val ROW_H = 50f

/** How far from an edge with more of the list beyond it a row starts to fade. */
private const val ROW_FADE = 64f

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/**
 * Sheets full of text use a deeper tint than menus: a busy Top Shelf behind them would otherwise
 * show through as coloured blobs under the labels.
 */
private val SheetGlass = Color(0xFF0B0B12).copy(alpha = 0.64f)

/** [opens]: OK leads somewhere (a list, a preview, Android settings), shown with a chevron. */
class PanelRowUi(val title: String, val value: String?, val adjustable: Boolean, val opens: Boolean = false)

/** Settings in a tvOS 26 glass sidebar that slides in from the right edge. */
@Composable
fun BoxScope.SettingsPanel(c: HomeController) {
    val strings = LocalStrings.current
    val open = c.panel == Panel.Settings

    // A fading colour rather than a fading layer (see fade()).
    val scrim = animateFloatAsState(if (open) 1f else 0f, tween(220), label = "panelScrim")
    Box(Modifier.fillMaxSize().drawBehind { if (scrim.value > 0.002f) drawRect(Color.Black.copy(alpha = 0.3f * scrim.value)) })
    AnimatedVisibility(
        open,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(spring(0.86f, 300f, IntOffset.VisibilityThreshold)) { it + 80 },
        exit = slideOutHorizontally(tween(260, easing = FastOutLinearInEasing)) { it + 80 },
    ) {
        val rows = c.settingsRows().map {
            PanelRowUi(it.title, it.value, adjustable = it.change != null, opens = it.change == null && it.select != null)
        }
        Box(
            Modifier
                .then(fade(160, 240))
                .padding(top = 30.dp, bottom = 30.dp, end = 30.dp)
                .width(PANEL_W.dp)
                .fillMaxHeight(),
        ) {
            // The glass goes into a layer of its own, which stays as it is while the list moves over
            // it: one texture per frame instead of the glass's five passes over the whole sheet, which
            // kept the TV's GPU from keeping up while scrolling. (No drop shadow: the scrim behind
            // already sets the sheet apart, and a shadow this size cost as much again.)
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .liquidGlass(RoundedCornerShape(34.dp), tint = SheetGlass, restsAt = DpOffset((960f - 30f - PANEL_W).dp, 30.dp)),
            )
            Column(Modifier.matchParentSize().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                BasicText(
                    strings.settings,
                    Modifier.padding(start = 20.dp, top = 26.dp, bottom = 14.dp),
                    style = Type.Title,
                )
                PanelList(rows, c.panelIndex.coerceIn(0, rows.lastIndex))
            }
        }
    }
}

@Composable
private fun PanelList(rows: List<PanelRowUi>, selected: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight.value
        val maxScroll = (rows.size * ROW_H - viewport).coerceAtLeast(0f)
        // The selected row stays in the middle, except near either end of the list.
        val target = (selected * ROW_H + ROW_H / 2f - viewport / 2f).coerceIn(0f, maxScroll)
        val scroll = animateFloatAsState(target, Motion.Scroll, label = "panelScroll")
        val hi = animateFloatAsState(selected.toFloat(), Motion.Highlight, label = "panelHighlight")
        // Rows fade out toward an edge that has more of the list beyond it, each row on its own (a
        // gradient mask over the list needed an offscreen buffer every frame: see fade()).
        fun rowAlpha(i: Int): Float {
            val center = i * ROW_H + ROW_H / 2f - scroll.value
            val top = if (scroll.value > 1f) smooth(center / ROW_FADE) else 1f
            val bottom = if (maxScroll - scroll.value > 1f) smooth((viewport - center) / ROW_FADE) else 1f
            return min(top, bottom)
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Box(
                Modifier
                    .offset { IntOffset(0, ((hi.value * ROW_H) - scroll.value).dp.roundToPx()) }
                    .fillMaxWidth()
                    .height(ROW_H.dp)
                    .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(25.dp)),
            )
            rows.forEachIndexed { i, row ->
                val focused = i == selected
                val color by animateColorAsState(if (focused) Palette.OnFocus else Color.White, tween(140), label = "rowText")
                Row(
                    Modifier
                        .offset { IntOffset(0, (i * ROW_H - scroll.value).dp.roundToPx()) }
                        .graphicsLayer {
                            alpha = rowAlpha(i)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .fillMaxWidth()
                        .height(ROW_H.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(row.title, Modifier.weight(1f), style = Type.Body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val value = when {
                        row.value == null -> null
                        focused && row.adjustable -> "‹  ${row.value}  ›"
                        else -> row.value
                    }
                    if (value != null) {
                        BasicText(
                            value,
                            Modifier.padding(start = 12.dp),
                            style = Type.Body.copy(color = color.copy(alpha = 0.6f), fontWeight = FontWeight.Normal),
                            maxLines = 1,
                        )
                    }
                    if (row.opens) {
                        BasicText(
                            "›",
                            Modifier.padding(start = 10.dp),
                            style = Type.Body.copy(color = color.copy(alpha = 0.45f), fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}

// endregion

/** Glass capsule shown while an icon is being moved. */
@Composable
fun BoxScope.MoveHint(visible: Boolean) {
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
        enter = slideInVertically(spring(0.8f, 380f, IntOffset.VisibilityThreshold)) { it * 2 },
        exit = slideOutVertically(tween(200)) { it * 2 },
    ) {
        Row(
            Modifier
                .then(fade(220, 200))
                .height(42.dp)
                .shadow(14.dp, RoundedCornerShape(21.dp), ambientColor = ShadowColor, spotColor = ShadowColor)
                .liquidGlass(RoundedCornerShape(21.dp), tint = DarkGlass)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Move, Modifier.size(17.dp)) { Color.White }
            Spacer(Modifier.width(10.dp))
            BasicText(LocalStrings.current.moveHint, style = Type.Caption)
        }
    }
}
