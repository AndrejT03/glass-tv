package com.andrej.glasstv.quicksettings

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.HomeActivity
import com.andrej.glasstv.ui.DisplayFont
import com.andrej.glasstv.ui.LocalStrings
import com.andrej.glasstv.ui.Motion
import com.andrej.glasstv.ui.OverlayWindow
import com.andrej.glasstv.ui.Palette
import com.andrej.glasstv.ui.TextFont
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Quits the app in front, for the quick settings' Close: home, as the Home button goes, then the
 * app's page in the TV's settings (App info), where Glass's accessibility service presses Force stop
 * and Clear cache, and OK in the question each of them asks, as the remote would; then Back, to the
 * home screen under it. Only the system may stop another app or clear its cache (FORCE_STOP_PACKAGES
 * and DELETE_CACHE_FILES are the system's), and TCL has nothing that does it for one app (its TV
 * Manager only has screens). The buttons are found by the settings app's own words for them, read out
 * of it, so they are found in whatever language the TV is in (TCL's TvSettings: "Force stop", "Clear
 * cache", "OK"; in Macedonian "Исклучи присилно", "Избриши кеш", "Во ред").
 *
 * A cover over the whole screen meanwhile, black with the app's icon and name, hides the settings
 * pages, and the remote waits ([busy]: the service holds its keys). About three seconds on the TV.
 */
class AppCloser(private val service: AccessibilityService) {
    private val scope = MainScope()

    /** Quitting an app: the settings are on screen under the cover. */
    var busy = false
        private set

    /** The TV's settings app, whose App info page does it (TCL's TvSettings, com.android.tv.settings). */
    val settingsPackage: String? by lazy {
        runCatching { service.packageManager.resolveActivity(details(service.packageName), 0)?.activityInfo?.packageName }.getOrNull()
    }

    private val forceStop by lazy { words("Force stop", "device_apps_app_management_force_stop") }
    private val clearCache by lazy { words("Clear cache", "device_apps_app_management_clear_cache") }
    private val ok by lazy { words("OK", "okay", "settings_ok", "ok") + label(service.getString(android.R.string.ok)) }

    private var window: OverlayWindow? = null
    private var app by mutableStateOf<OpenApp?>(null)
    private var outcome by mutableStateOf<Outcome?>(null)
    private var shown by mutableStateOf(false)

    fun close(open: OpenApp) {
        if (busy) return
        busy = true
        app = open
        outcome = null
        shown = true
        val cover = window ?: OverlayWindow(service, 1f, Gravity.START) { Cover(app, outcome, shown) }.also { window = it }
        cover.show()
        scope.launch {
            val started = SystemClock.uptimeMillis()
            // Nothing of the settings shows till the cover is whole.
            delay(COVER_IN_MS + 40L)
            val result = withTimeoutOrNull(ALL_MS) { quit(open.packageName) } ?: Outcome(stopped = false, cleared = false)
            leave()
            Log.i(TAG, "${open.packageName}: stopped ${result.stopped}, cache cleared ${result.cleared}, ${SystemClock.uptimeMillis() - started} ms")
            outcome = result
            delay(OUTCOME_MS)
            busy = false
            shown = false
            cover.hide(afterMs = COVER_OUT_MS + 40L)
        }
    }

    private suspend fun quit(pkg: String): Outcome {
        // Home first: the app is left as Home leaves it, and its page then opens over the home screen,
        // which Back goes back to (over another app, Back went to the app behind it).
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        waitFor(HOME_MS) { HomeActivity.front }
        // A task of its own, gone with the page: the settings the user has open stay as they are.
        val page = details(pkg).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        if (runCatching { service.startActivity(page) }.isFailure) return Outcome(stopped = false, cleared = false)
        val name = setOf(label(pkg))
        if (waitFor(PAGE_MS) { pageUp(name) } == null) return Outcome(stopped = false, cleared = false)
        return Outcome(stopped = stop(name), cleared = clear(name))
    }

    /** The app's page is on screen (not a question over it): it names the package ([name]), under the app's version, or shows Force stop. */
    private fun pageUp(name: Set<String>) = if (titles(name).isNotEmpty() || row(forceStop) != null) true else null

    /** Force stop, then OK. True once the page has no Force stop: also when the app had stopped already. */
    private suspend fun stop(name: Set<String>): Boolean {
        val button = waitFor(FIND_MS) { row(forceStop)?.takeIf { it.refresh() && it.isEnabled } } ?: return row(forceStop) == null
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK) || !confirm()) return false
        return waitFor(DONE_MS) { if (titles(name).isNotEmpty() && row(forceStop) == null) true else null } != null
    }

    /**
     * Clear cache, then OK. Its row takes a click only while there is a cache: one that stays dimmed
     * has nothing in it. Further down the page than the screen reaches (an app with more buttons), the
     * list is scrolled to it. True once the cache is gone, or was.
     */
    private suspend fun clear(name: Set<String>): Boolean {
        var scrolled = 0
        val button = waitFor(CACHE_MS) {
            val row = row(clearCache)
            if (row == null && scrolled < MAX_SCROLLS) {
                scrolled++
                list(name)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            row?.takeIf { it.refresh() && it.isEnabled }
        } ?: return row(clearCache) != null
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK) || !confirm()) return false
        // Dimmed again once the settings have asked for it to be cleared (while it is, and after, at 0 B).
        return waitFor(DONE_MS) { row(clearCache)?.takeIf { it.refresh() && !it.isEnabled } } != null
    }

    /** OK in the question the page asks (OK is in focus there; Cancel under it). */
    private suspend fun confirm(): Boolean {
        val button = waitFor(DIALOG_MS) { row(ok) } ?: return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Back out of the page (its task goes with it) to the home screen under it; Home if that doesn't get there. */
    private suspend fun leave() {
        repeat(2) {
            if (HomeActivity.front != null) return
            if (service.rootInActiveWindow?.packageName == settingsPackage) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor(BACK_MS) { HomeActivity.front }
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        waitFor(HOME_MS) { HomeActivity.front }
    }

    /** [look] every few frames till it finds something; null after [ms]. */
    private suspend fun <T : Any> waitFor(ms: Long, look: () -> T?): T? = withTimeoutOrNull(ms) {
        var found = look()
        while (found == null) {
            delay(LOOK_EVERY_MS)
            found = look()
        }
        found
    }

    /** The settings app's windows on screen: never the cover, nor another app's. */
    private fun roots(): List<AccessibilityNodeInfo> =
        runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList()).filter { it.packageName == settingsPackage }

    /**
     * The texts on screen in the settings that read one of [words], whole. A search by text is asked of
     * the settings app each time, of its views as they are; walking the tree read the service's copy of
     * them, which only a window change brings up to date (Glass hears of nothing else): once Force
     * stop's question had gone, the page back under it stayed unseen.
     */
    private fun titles(words: Set<String>): List<AccessibilityNodeInfo> =
        roots().flatMap { root -> words.flatMap { root.findAccessibilityNodeInfosByText(it).orEmpty() } }.filter { it.label() in words }

    /**
     * The row (or the question's button) whose title is one of [words]: the title's nearest ancestor
     * that takes clicks, a few levels up at most and never past a list. A title of nothing to click
     * (the question's heading, which repeats the button's name) doesn't count.
     */
    private fun row(words: Set<String>): AccessibilityNodeInfo? = titles(words).firstNotNullOfOrNull { title ->
        above(title, ROW_DEPTH) {
            when {
                it.collectionInfo != null -> false
                it.isClickable -> true
                else -> null
            }
        }
    }

    /** The page's list, which scrolls: above the package's name ([name]), its first row. */
    private fun list(name: Set<String>): AccessibilityNodeInfo? =
        titles(name).firstNotNullOfOrNull { title -> above(title, LIST_DEPTH) { if (it.isScrollable) true else null } }

    /**
     * [from] or the first of its ancestors, up to [levels] of them, for which [take] is true; null
     * once [take] says false (never past it) or none is. Each is read afresh from the settings.
     */
    private fun above(from: AccessibilityNodeInfo, levels: Int, take: (AccessibilityNodeInfo) -> Boolean?): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = from
        repeat(levels) {
            val n = node ?: return null
            n.refresh()
            when (take(n)) {
                true -> return n
                false -> return null
                null -> node = n.parent
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.label() = text?.let { label(it.toString()) }

    private fun label(text: String) = text.trim().lowercase()

    /** The settings app's own words for a button ([names] of its strings), and [english] where it has none. */
    private fun words(english: String, vararg names: String): Set<String> {
        val pkg = settingsPackage
        val res = pkg?.let { runCatching { service.packageManager.getResourcesForApplication(it) }.getOrNull() }
        val own = names.mapNotNull { name ->
            res?.getIdentifier(name, "string", pkg)?.takeIf { it != 0 }?.let { id -> runCatching { res.getString(id) }.getOrNull() }
        }
        return (own + english).map(::label).toSet()
    }

    private fun details(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))

    fun destroy() {
        scope.cancel()
        window?.destroy()
        window = null
        busy = false
    }

    private companion object {
        const val TAG = "AppCloser"

        /** How long the cover says how it went before it goes. */
        const val OUTCOME_MS = 650L

        const val HOME_MS = 2_000L
        const val PAGE_MS = 3_000L
        /** For Force stop on a page that is up: a stopped app has none. */
        const val FIND_MS = 700L
        const val DIALOG_MS = 2_000L
        const val DONE_MS = 2_500L
        /** For Clear cache to take a click: its size is worked out again once the app has stopped. */
        const val CACHE_MS = 1_200L
        const val BACK_MS = 1_200L
        const val ALL_MS = 12_000L
        const val LOOK_EVERY_MS = 100L

        const val MAX_SCROLLS = 3
        /** From a title up to its row: the row itself, or the question's button around its title. */
        const val ROW_DEPTH = 4
        /** From the package's name up to the list it is a row of. */
        const val LIST_DEPTH = 6
    }
}

/** How it went: the app stopped (or already was), its cache cleared (or already empty). */
private class Outcome(val stopped: Boolean, val cleared: Boolean)

/**
 * The cover while an app quits: the screen goes black, the app's icon and name in the middle, and
 * under them that it is closing, then how it went.
 */
@Composable
private fun Cover(app: OpenApp?, outcome: Outcome?, shown: Boolean) {
    val strings = LocalStrings.current
    val dark = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) dark.animateTo(1f, tween(COVER_IN_MS, easing = Motion.EaseOut)) else dark.animateTo(0f, tween(COVER_OUT_MS))
    }
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = dark.value)) }) {
        if (app == null) return@Box
        Column(
            Modifier.align(Alignment.Center).graphicsLayer {
                alpha = dark.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(app.packageName)
            Spacer(Modifier.height(18.dp))
            BasicText(app.label, style = NameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val caption = when {
                outcome == null -> strings.closing
                outcome.stopped && outcome.cleared -> "${strings.closed} · ${strings.cacheCleared}"
                outcome.stopped -> strings.closed
                else -> strings.couldNotClose
            }
            BasicText(caption, style = CaptionStyle, maxLines = 1)
        }
    }
}

/** The app's round icon, as the control center's Last App shows it, with its thin light rim. */
@Composable
private fun AppIcon(pkg: String) {
    val density = LocalDensity.current.density
    val image by produceState<ImageBitmap?>(null, pkg) {
        value = GlassApp.instance.recents.icon(pkg, (ICON * density).roundToInt())
    }
    Canvas(Modifier.size(ICON.dp)) {
        val picture = image ?: return@Canvas
        drawImage(picture, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
        drawCircle(Color.White.copy(alpha = 0.22f), radius = size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

private const val ICON = 76f

/** The cover darkening, and clearing again onto the home screen. */
private const val COVER_IN_MS = 240
private const val COVER_OUT_MS = 320

private val NameStyle = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp, color = Palette.Label)
private val CaptionStyle = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Palette.Secondary)
