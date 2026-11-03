package com.andrej.glasstv.ui.home

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.data.AppsRepository
import com.andrej.glasstv.data.IDLE_MINUTES
import com.andrej.glasstv.data.Prefs
import com.andrej.glasstv.data.TvInput
import com.andrej.glasstv.controlcenter.ControlCenterModel
import com.andrej.glasstv.controlcenter.Step
import com.andrej.glasstv.controlcenter.Tile
import com.andrej.glasstv.inputs.InputRow
import com.andrej.glasstv.inputs.InputsMenuModel
import com.andrej.glasstv.inputs.inputsIconRect
import com.andrej.glasstv.screensaver.DYNAMIC_ALL
import com.andrej.glasstv.screensaver.SAVERS
import com.andrej.glasstv.ui.glass.WALLPAPERS
import com.andrej.glasstv.ui.stringsFor
import kotlin.math.ceil

/** tvOS home grid geometry, in design units (960x540 canvas). */
object Grid {
    const val COLS = 6
    const val TILE_W = 124f
    const val TILE_H = 74.4f
    const val GAP_X = 22f
    const val ROW_PITCH = 126f
    const val LEFT = (960f - (COLS * TILE_W + (COLS - 1) * GAP_X)) / 2f
    const val TOP_ROW_Y = 398f

    /** Corner radius of a tile. The lens and the dock around it are concentric with it, as in tvOS 26. */
    const val TILE_RADIUS = 11f

    /** How much a focused tile grows in the dock (less than in the grid: the lens frames it). */
    const val DOCK_FOCUS_SCALE = 0.1f

    /** The Liquid Glass lens behind the focused tile: this much glass around the grown tile. */
    const val LENS_PAD = 7f

    /** Glass between the lens and the edge of the dock. */
    const val DOCK_INSET = 6f
    const val LENS_RADIUS = TILE_RADIUS * (1 + DOCK_FOCUS_SCALE) + LENS_PAD

    /** The tvOS 26 dock: the Liquid Glass shelf around the top row. */
    const val DOCK_RADIUS = LENS_RADIUS + DOCK_INSET
    const val DOCK_PAD_X = TILE_W * DOCK_FOCUS_SCALE / 2 + LENS_PAD + DOCK_INSET
    const val DOCK_PAD_Y = TILE_H * DOCK_FOCUS_SCALE / 2 + LENS_PAD + DOCK_INSET
    val dock = Rect(LEFT - DOCK_PAD_X, TOP_ROW_Y - DOCK_PAD_Y, 960f - LEFT + DOCK_PAD_X, TOP_ROW_Y + TILE_H + DOCK_PAD_Y)

    /** The lens around dock tile [i] (its column), grown tile plus [LENS_PAD]. */
    fun lensRect(i: Int): Rect {
        val grow = DOCK_FOCUS_SCALE / 2
        val l = x(i) - TILE_W * grow - LENS_PAD
        val t = TOP_ROW_Y - TILE_H * grow - LENS_PAD
        return Rect(l, t, l + TILE_W * (1 + DOCK_FOCUS_SCALE) + 2 * LENS_PAD, t + TILE_H * (1 + DOCK_FOCUS_SCALE) + 2 * LENS_PAD)
    }

    fun x(i: Int) = LEFT + (i % COLS) * (TILE_W + GAP_X)
    fun y(i: Int) = TOP_ROW_Y + (i / COLS) * ROW_PITCH

    /** The top row sits over the Top Shelf; any lower row scrolls the grid up, tvOS style. */
    fun scrollFor(row: Int) = if (row <= 0) 0f else (TOP_ROW_Y - 66f) + (row - 2).coerceAtLeast(0) * ROW_PITCH

    fun tileRect(i: Int): Rect {
        val top = y(i) - scrollFor(i / COLS)
        return Rect(x(i), top, x(i) + TILE_W, top + TILE_H)
    }

    /** Tile artwork width in pixels: the biggest a tile gets (focused and lifted) on this screen. */
    fun artWidth(screenWidthPx: Int) = ceil(TILE_W * 1.25f * screenWidthPx / 960f).toInt()
}

enum class Dir(val x: Float, val y: Float) { None(0f, 0f), Left(-1f, 0f), Right(1f, 0f), Up(0f, -1f), Down(0f, 1f) }

@Immutable
data class DirEvent(val dir: Dir, val nonce: Int) {
    companion object {
        val None = DirEvent(Dir.None, 0)
    }
}

sealed interface Focus {
    data class App(val index: Int) : Focus
    data class Control(val index: Int) : Focus
}

enum class Panel { None, Settings, Inputs, ControlCenter }

enum class ControlId { Screensaver, Inputs, Settings, AndroidSettings, ControlCenter }

enum class MenuAction { Move, Info, Uninstall }

class SettingRow(
    val title: String,
    val value: String?,
    val change: ((Int) -> Unit)? = null,
    val select: (() -> Unit)? = null,
)

interface HomeActions {
    fun launch(app: AppEntry, tile: Rect)
    fun appInfo(app: AppEntry)
    fun uninstall(app: AppEntry)
    /** The TV's own inputs panel, where Glass can't list the inputs itself. */
    fun openInputs()
    fun switchInput(input: TvInput)
    fun openAndroidSettings()
    /** [preview]: started from Settings to try the savers out; Left/Right switch, OK chooses. */
    fun startScreensaver(preview: Boolean = false)
    /** What a control center tile is for (Controls.run); false when it couldn't. */
    fun control(tile: Tile): Boolean
    /** Why Power Off couldn't put the TV to sleep, for under the tiles. */
    fun powerOffNotice(): String
    /** Asks the user to let Glass use Bluetooth (the ambient light's strip). */
    fun requestBluetooth()
    fun tick()
    fun select()
}

/** All home-screen navigation. The Compose UI only renders this state. */
@Stable
class HomeController(
    private val repo: AppsRepository,
    private val prefs: Prefs,
    private val actions: HomeActions,
    hasInputs: Boolean,
    private val inputs: () -> List<TvInput>,
    recents: () -> List<AppEntry>,
    /** The control center (the capsule's button, or the remote's Home button held). */
    val controlCenter: ControlCenterModel,
) {
    var focus by mutableStateOf<Focus>(Focus.App(0))
        private set
    var enter by mutableStateOf(DirEvent.None)
        private set
    var bump by mutableStateOf(DirEvent.None)
        private set
    var movingKey by mutableStateOf<String?>(null)
        private set
    var menuKey by mutableStateOf<String?>(null)
        private set
    var menuIndex by mutableIntStateOf(0)
        private set
    var panel by mutableStateOf(Panel.None)
        private set
    var panelIndex by mutableIntStateOf(0)
        private set

    /** The quick settings are up over the home screen (a window of the accessibility service's). */
    var quickSettings by mutableStateOf(false)
        private set

    /** The inputs menu (the capsule's TV button, or the remote's Input button on the home screen). */
    val inputsMenu = InputsMenuModel(inputs, recents)

    /** When a key was last pressed (uptime ms): the live aerial rests while the remote is in use. */
    var lastInput by mutableLongStateOf(0L)
        private set

    /** Set when coming back from an app; the home screen zooms back into place, like on tvOS. */
    var returning by mutableStateOf(false)

    val apps: List<AppEntry> by derivedStateOf { repo.visible() }

    /**
     * Inside the status capsule, left to right; Up from the dock lands on the last one, the control
     * center, next to the clock as on tvOS 26.
     */
    val controls: List<ControlId> = buildList {
        add(ControlId.AndroidSettings)
        if (hasInputs) add(ControlId.Inputs)
        add(ControlId.Screensaver)
        add(ControlId.Settings)
        add(ControlId.ControlCenter)
    }

    private var nonce = 0
    private var centerDown = false
    private var centerLong = false
    private var topCol by mutableIntStateOf(0)

    /** The app whose Top Shelf is showing: the focused one in the dock, else the last one focused there. */
    fun shelfApp(): AppEntry? {
        val i = focusedAppIndex()
        return apps.getOrNull(if (i in 0 until Grid.COLS) i else topCol.coerceAtMost(Grid.COLS - 1))
    }

    fun focusedAppIndex(): Int {
        val f = focus as? Focus.App ?: return -1
        return if (apps.isEmpty()) -1 else f.index.coerceIn(0, apps.lastIndex)
    }

    fun menuItems(app: AppEntry): List<MenuAction> = buildList {
        add(MenuAction.Move)
        add(MenuAction.Info)
        if (!app.system) add(MenuAction.Uninstall)
    }

    /**
     * Settings stay open if the TV recreates the launcher while the screen saver picker or Android
     * settings is in front (it runs short of memory and may drop the launcher's process).
     */
    fun saveState(out: Bundle) {
        out.putBoolean(STATE_SETTINGS, panel == Panel.Settings)
        out.putInt(STATE_ROW, panelIndex)
    }

    fun restoreState(saved: Bundle) {
        if (!saved.getBoolean(STATE_SETTINGS)) return
        panel = Panel.Settings
        panelIndex = saved.getInt(STATE_ROW).coerceIn(0, settingsRows().lastIndex)
        focus = Focus.Control(controls.indexOf(ControlId.Settings))
    }

    /** Returns to the top row and closes everything; the TV/Home button on the home screen. */
    fun goHome() {
        panel = Panel.None
        menuKey = null
        movingKey = null
        if (apps.isNotEmpty()) focus = Focus.App(topCol.coerceAtMost(apps.lastIndex))
    }

    fun onKey(event: KeyEvent): Boolean {
        val e = event.nativeKeyEvent
        val down = e.action == AndroidKeyEvent.ACTION_DOWN
        if (down) lastInput = e.eventTime
        when (e.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                // Select fires on release so a hold can become a long press (tvOS context menu).
                if (down) {
                    if (e.repeatCount == 0) {
                        centerDown = true
                        centerLong = false
                    } else if (centerDown && !centerLong) {
                        centerLong = true
                        onLongPress()
                    }
                } else {
                    if (centerDown && !centerLong) onSelect()
                    centerDown = false
                    centerLong = false
                }
                return true
            }
        }
        val handled = when (e.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) onDir(Dir.Left); true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) onDir(Dir.Right); true }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (down) onDir(Dir.Up); true }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (down) onDir(Dir.Down); true }
            AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> { if (down && e.repeatCount == 0) onBack(); true }
            AndroidKeyEvent.KEYCODE_MENU -> { if (down && e.repeatCount == 0) onMenuKey(); true }
            // The Settings button, where Glass's accessibility service is off (it takes the button everywhere when it is on).
            AndroidKeyEvent.KEYCODE_SETTINGS, AndroidKeyEvent.KEYCODE_NOTIFICATION -> {
                if (down && e.repeatCount == 0) toggleControlCenter()
                true
            }
            else -> false
        }
        return handled
    }

    // region navigation

    private fun onDir(d: Dir) = when {
        panel == Panel.Settings -> settingsDir(d)
        panel == Panel.Inputs -> inputsDir(d)
        panel == Panel.ControlCenter -> controlCenterDir(d)
        menuKey != null -> menuDir(d)
        movingKey != null -> moveDir(d)
        else -> focusDir(d)
    }

    private fun focusDir(d: Dir) {
        val n = apps.size
        when (val f = focus) {
            is Focus.App -> {
                if (n == 0) {
                    if (d == Dir.Up) setFocus(Focus.Control(controls.lastIndex), d) else bumpTo(d)
                    return
                }
                val i = f.index.coerceIn(0, n - 1)
                val col = i % Grid.COLS
                val row = i / Grid.COLS
                when (d) {
                    Dir.Left -> if (col > 0) setFocus(Focus.App(i - 1), d) else bumpTo(d)
                    Dir.Right -> if (col < Grid.COLS - 1 && i + 1 < n) setFocus(Focus.App(i + 1), d) else bumpTo(d)
                    Dir.Up -> if (row > 0) {
                        setFocus(Focus.App(i - Grid.COLS), d)
                    } else {
                        topCol = col
                        setFocus(Focus.Control(controls.lastIndex), d)
                    }
                    Dir.Down -> when {
                        i + Grid.COLS < n -> setFocus(Focus.App(i + Grid.COLS), d)
                        (row + 1) * Grid.COLS < n -> setFocus(Focus.App(n - 1), d)
                        else -> bumpTo(d)
                    }
                    Dir.None -> Unit
                }
            }
            is Focus.Control -> when (d) {
                Dir.Left -> if (f.index > 0) setFocus(Focus.Control(f.index - 1), d) else bumpTo(d)
                Dir.Right -> if (f.index < controls.lastIndex) setFocus(Focus.Control(f.index + 1), d) else bumpTo(d)
                Dir.Down -> if (n > 0) setFocus(Focus.App(topCol.coerceAtMost(n - 1)), d) else bumpTo(d)
                else -> bumpTo(d)
            }
        }
    }

    private fun setFocus(f: Focus, d: Dir) {
        focus = f
        enter = DirEvent(d, ++nonce)
        if (f is Focus.App && f.index < Grid.COLS) topCol = f.index
        actions.tick()
    }

    private fun bumpTo(d: Dir) {
        bump = DirEvent(d, ++nonce)
    }

    private fun onSelect() {
        when {
            panel == Panel.Settings -> settingsRows().getOrNull(panelIndex)?.let { row ->
                actions.select()
                row.select?.invoke() ?: row.change?.invoke(1)
            }
            panel == Panel.Inputs -> selectInput()
            panel == Panel.ControlCenter -> selectControl()
            menuKey != null -> runMenu()
            movingKey != null -> finishMove()
            else -> when (val f = focus) {
                is Focus.App -> {
                    val i = focusedAppIndex()
                    if (i < 0) return
                    actions.select()
                    actions.launch(apps[i], Grid.tileRect(i))
                }
                is Focus.Control -> {
                    actions.select()
                    when (controls[f.index]) {
                        ControlId.Screensaver -> actions.startScreensaver()
                        ControlId.Inputs -> if (inputs().isNotEmpty()) openInputsMenu() else actions.openInputs()
                        ControlId.Settings -> {
                            panel = Panel.Settings
                            panelIndex = 0
                            // Dynamic wallpapers pushed since Glass started get their row.
                            GlassApp.instance.dynamic.refresh()
                        }
                        ControlId.AndroidSettings -> actions.openAndroidSettings()
                        ControlId.ControlCenter -> openControlCenter()
                    }
                }
            }
        }
    }

    private fun onLongPress() {
        if (panel != Panel.None || menuKey != null || movingKey != null) return
        val i = focusedAppIndex()
        if (i >= 0) openMenu(apps[i])
    }

    private fun onMenuKey() {
        when {
            menuKey != null -> menuKey = null
            panel == Panel.None && movingKey == null -> focusedAppIndex().takeIf { it >= 0 }?.let { openMenu(apps[it]) }
        }
    }

    private fun onBack() {
        when {
            panel == Panel.ControlCenter && controlCenter.back() -> Unit
            panel != Panel.None -> panel = Panel.None
            menuKey != null -> menuKey = null
            movingKey != null -> finishMove()
            focus is Focus.Control -> setFocus(Focus.App(topCol.coerceAtMost((apps.size - 1).coerceAtLeast(0))), Dir.Down)
            focusedAppIndex() >= Grid.COLS -> setFocus(Focus.App(focusedAppIndex() % Grid.COLS), Dir.Up)
            // Like Menu on the Apple TV home screen: Back on the top row starts the screen saver.
            else -> actions.startScreensaver()
        }
    }

    // endregion

    // region context menu and moving

    private fun openMenu(app: AppEntry) {
        actions.select()
        menuKey = app.key
        menuIndex = 0
    }

    private fun menuDir(d: Dir) {
        val app = apps.firstOrNull { it.key == menuKey } ?: return
        val count = menuItems(app).size
        val next = when (d) {
            Dir.Up -> menuIndex - 1
            Dir.Down -> menuIndex + 1
            else -> return
        }
        if (next in 0 until count) {
            menuIndex = next
            actions.tick()
        }
    }

    private fun runMenu() {
        val app = apps.firstOrNull { it.key == menuKey } ?: return
        val action = menuItems(app).getOrNull(menuIndex) ?: return
        actions.select()
        menuKey = null
        when (action) {
            MenuAction.Move -> movingKey = app.key
            MenuAction.Info -> actions.appInfo(app)
            MenuAction.Uninstall -> actions.uninstall(app)
        }
    }

    private fun moveDir(d: Dir) {
        val list = apps.toMutableList()
        val i = list.indexOfFirst { it.key == movingKey }
        if (i < 0) return
        val target = when (d) {
            Dir.Left -> i - 1
            Dir.Right -> i + 1
            Dir.Up -> i - Grid.COLS
            Dir.Down -> (i + Grid.COLS).coerceAtMost(list.lastIndex)
            Dir.None -> i
        }
        if (target !in list.indices || target == i) {
            bumpTo(d)
            return
        }
        list.add(target, list.removeAt(i))
        repo.saveOrder(list)
        focus = Focus.App(target)
        actions.tick()
    }

    private fun finishMove() {
        movingKey = null
        actions.select()
    }

    // endregion

    // region inputs menu

    fun openInputsMenu() {
        inputsMenu.reset()
        panel = Panel.Inputs
    }

    fun toggleInputsMenu() {
        when {
            panel == Panel.Inputs -> panel = Panel.None
            inputs().isNotEmpty() -> openInputsMenu()
        }
    }

    private fun inputsDir(d: Dir) {
        val step = when (d) {
            Dir.Up -> -1
            Dir.Down -> 1
            else -> return
        }
        if (inputsMenu.move(step)) actions.tick()
    }

    private fun selectInput() {
        val count = inputsMenu.rows().size
        val index = inputsMenu.index
        when (val row = inputsMenu.select()) {
            null, InputRow.More -> actions.tick()
            is InputRow.Input -> {
                actions.select()
                panel = Panel.None
                actions.switchInput(row.input)
            }
            is InputRow.App -> {
                actions.select()
                panel = Panel.None
                // It zooms out of its icon in the menu.
                actions.launch(row.app, inputsIconRect(index, count))
            }
        }
    }

    // endregion

    // region control center

    fun openControlCenter() {
        controlCenter.reset()
        panel = Panel.ControlCenter
    }

    fun closePanel() {
        panel = Panel.None
    }

    fun quickSettingsShown(shown: Boolean) {
        quickSettings = shown
    }

    fun toggleControlCenter() {
        if (panel == Panel.ControlCenter) panel = Panel.None else openControlCenter()
    }

    private fun controlCenterDir(d: Dir) {
        val step = when (d) {
            Dir.Left -> Step.Left
            Dir.Right -> Step.Right
            Dir.Up -> Step.Up
            Dir.Down -> Step.Down
            Dir.None -> return
        }
        val adjusting = controlCenter.adjusting
        if (controlCenter.move(step) && !adjusting) actions.tick()
    }

    private fun selectControl() {
        val tile = controlCenter.select()
        if (tile == null) {
            actions.tick()
            val light = controlCenter.focus == Tile.Ambient || controlCenter.focus == Tile.AmbientColor
            if (light && !controlCenter.ambient.permitted()) actions.requestBluetooth()
            return
        }
        actions.select()
        if (actions.control(tile)) {
            panel = Panel.None
        } else if (tile == Tile.Power) {
            controlCenter.notice = actions.powerOffNotice()
        }
    }

    // endregion

    // region settings

    fun settingsRows(): List<SettingRow> {
        val s = stringsFor(prefs.language)
        val onOff = { v: Boolean -> if (v) s.on else s.off }
        // Apple's dynamic desktop pictures, once push_dynamic.sh has put them on the TV: one of them, or all in turn.
        val dynamic = GlassApp.instance.dynamic.list.orEmpty()
        val dynamicIds = listOf(DYNAMIC_ALL) + dynamic.map { it.id }
        return listOfNotNull(
            SettingRow(s.home, if (prefs.homeStyle == "clock") s.homeClock else s.homeTvos, change = { d ->
                prefs.homeStyle = cycle(listOf("tvos", "clock"), prefs.homeStyle, d)
            }),
            SettingRow(s.wallpaper, WALLPAPERS.firstOrNull { it.id == prefs.wallpaper }?.name ?: WALLPAPERS[0].name, change = { d ->
                prefs.wallpaper = cycle(WALLPAPERS.map { it.id }, prefs.wallpaper, d)
            }),
            SettingRow(s.clock, if (prefs.clockStyle == "glass") s.clockGlass else s.clockSolid, change = { d ->
                prefs.clockStyle = cycle(listOf("glass", "solid"), prefs.clockStyle, d)
            }),
            SettingRow(
                s.screensaver,
                s.savers[prefs.saver] ?: s.savers.values.first(),
                change = { d -> prefs.saver = cycle(SAVERS, prefs.saver, d) },
                // OK opens the picker, like the Choose row; the panel stays open for when it ends.
                select = { actions.startScreensaver(preview = true) },
            ),
            SettingRow(s.choose, null, select = { actions.startScreensaver(preview = true) }),
            SettingRow(s.startAfter, if (prefs.idleMinutes == 0) s.never else "${prefs.idleMinutes} ${s.minutes}", change = { d ->
                prefs.idleMinutes = cycle(IDLE_MINUTES, prefs.idleMinutes, d)
            }),
            SettingRow(s.quality, if (prefs.aerialQuality == "uhd") "4K" else "1080p", change = { d ->
                prefs.aerialQuality = cycle(listOf("hd", "uhd"), prefs.aerialQuality, d)
            }),
            if (dynamic.isEmpty()) null else SettingRow(
                s.dynamicWallpaper,
                dynamic.firstOrNull { it.id == prefs.dynamicWallpaper }?.name ?: s.allInTurn,
                change = { d -> prefs.dynamicWallpaper = cycle(dynamicIds, prefs.dynamicWallpaper, d) },
            ),
            SettingRow(
                s.saverClock,
                when (prefs.saverClock) {
                    "small" -> s.clockSmall
                    "off" -> s.off
                    else -> s.clockBig
                },
                change = { d -> prefs.saverClock = cycle(listOf("big", "small", "off"), prefs.saverClock, d) },
            ),
            SettingRow(s.sounds, onOff(prefs.sounds), change = { prefs.sounds = !prefs.sounds }),
            SettingRow(s.language, if (prefs.language == "en") "English" else "Македонски", change = { d ->
                prefs.language = cycle(listOf("mk", "en"), prefs.language, d)
            }),
            SettingRow(s.systemSettings, null, select = { actions.openAndroidSettings() }),
        )
    }

    private fun settingsDir(d: Dir) {
        val rows = settingsRows()
        when (d) {
            Dir.Up, Dir.Down -> {
                val next = panelIndex + if (d == Dir.Up) -1 else 1
                if (next in rows.indices) {
                    panelIndex = next
                    actions.tick()
                }
            }
            Dir.Left, Dir.Right -> rows.getOrNull(panelIndex)?.change?.let {
                it(if (d == Dir.Left) -1 else 1)
                actions.tick()
            }
            Dir.None -> Unit
        }
    }

    private fun <T> cycle(values: List<T>, current: T, delta: Int): T {
        val i = values.indexOf(current).coerceAtLeast(0)
        return values[((i + delta) % values.size + values.size) % values.size]
    }

    // endregion

    private companion object {
        const val STATE_SETTINGS = "settings"
        const val STATE_ROW = "settingsRow"
    }
}
