package com.andrej.glasstv.inputs

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andrej.glasstv.BuildConfig
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.HomeActivity
import com.andrej.glasstv.controlcenter.ControlCenterOverlay
import com.andrej.glasstv.quicksettings.QuickSettingsOverlay

/**
 * The remote's Input button, over any app: it opens Glass's inputs menu instead of the TV's own
 * panel (on the home screen, the home screen's own menu, whose frost is a real blur of what is
 * behind it). Only that button is taken from the apps, and the arrows, OK and Back while the menu
 * is open; every other key goes on as usual. Where Android lists no inputs, the button is left to
 * the TV. It also notes which app comes to the front, for the recent apps behind the menu's More.
 *
 * The remote's Settings button opens the control center (the TCL's remote sends it as NOTIFICATION,
 * the key of Google TV's dashboard, which is off; other remotes as SETTINGS), and so does holding the
 * Home button, as
 * holding the TV button does on Apple TV (over the home screen, the home screen's own). A short
 * press of Home still goes home: the service takes the button and goes home itself once it is let
 * go. Power Off puts the TV to sleep through this service too, as only an accessibility service may.
 *
 * The TCL remote's button right of 0 opens Glass's quick settings in place of TCL's own bar. It sends
 * TCL's own key code, 4514, which TCL's window manager turns into its Quick Settings only once the
 * key is on its way to the app in front (`TclPhoneWindowManagerEx.handleKeyBeforeDispatching`), after
 * this service has seen it: taken here, TCL's bar never comes up.
 *
 * The user turns it on in Settings > Accessibility.
 */
class InputsKeyService : AccessibilityService() {
    private var overlay: InputsOverlay? = null
    private var controlCenter: ControlCenterOverlay? = null
    private var quickSettings: QuickSettingsOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var homeTaken = false
    private var homeHeld = false
    private val holdHome = Runnable {
        homeHeld = true
        val home = HomeActivity.front
        if (home != null) home.toggleControlCenter() else controlCenter?.toggle()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = InputsOverlay(this)
        controlCenter = ControlCenterOverlay(this)
        quickSettings = QuickSettingsOverlay(this)
        running = this
        // What is in front as the service (and Glass with it) starts again: no window changes till later.
        rootInActiveWindow?.packageName?.toString()?.let(GlassApp.instance.recents::opened)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val menu = overlay ?: return false
        val center = controlCenter ?: return false
        val quick = quickSettings ?: return false
        // While the quick settings' Close quits an app, the TV's settings are on screen under its
        // cover, being pressed: the remote waits (Power aside).
        if (quick.isClosing) return event.keyCode != KeyEvent.KEYCODE_POWER
        // The rest of a Home press the service took, also once holding it has opened the control center.
        if (event.keyCode == KeyEvent.KEYCODE_HOME && homeTaken) return home(event)
        if (event.keyCode in QUICK_SETTINGS_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggle(Menu.QuickSettings)
            return true
        }
        if (event.keyCode in CONTROL_CENTER_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggle(Menu.ControlCenter)
            return true
        }
        if (quick.isOpen) {
            // The Input button goes from the quick settings to the inputs menu.
            if (event.keyCode != KeyEvent.KEYCODE_TV_INPUT) return quick.onKey(event)
            quick.hide()
        }
        if (menu.isOpen) return menu.onKey(event)
        if (center.isOpen) {
            // The Input button goes from the control center to the inputs menu.
            if (event.keyCode != KeyEvent.KEYCODE_TV_INPUT) return center.onKey(event)
            center.hide()
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_TV_INPUT -> {
                if (GlassApp.instance.inputs.list.isEmpty()) return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val home = HomeActivity.front
                    if (home != null) home.toggleInputsMenu() else menu.show()
                }
                true
            }
            // Only from its start: a press that began while a menu was open went to the system, and its release goes too.
            KeyEvent.KEYCODE_HOME -> event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && home(event)
            else -> false
        }
    }

    enum class Menu { ControlCenter, Inputs, QuickSettings }

    /**
     * Opens one of Glass's menus over whatever is on screen, closing the others, or closes it when it
     * is open, as its remote button does; the iPhone app's buttons for them too. False when there is
     * no such menu (no inputs Android lists).
     */
    fun toggle(which: Menu): Boolean {
        val menu = overlay ?: return false
        val center = controlCenter ?: return false
        val quick = quickSettings ?: return false
        val home = HomeActivity.front
        when (which) {
            Menu.QuickSettings -> {
                if (menu.isOpen) menu.hide()
                if (center.isOpen) center.hide()
                home?.closePanel()
                quick.toggle()
            }
            Menu.ControlCenter -> {
                if (menu.isOpen) menu.hide()
                if (quick.isOpen) quick.hide()
                if (home != null) home.toggleControlCenter() else center.toggle()
            }
            Menu.Inputs -> {
                if (GlassApp.instance.inputs.list.isEmpty()) return false
                if (quick.isOpen) quick.hide()
                if (center.isOpen) center.hide()
                when {
                    home != null -> home.toggleInputsMenu()
                    menu.isOpen -> menu.hide()
                    else -> menu.show()
                }
            }
        }
        return true
    }

    /** Home: held, the control center; let go sooner, home, as the system would have gone. */
    private fun home(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            homeTaken = true
            homeHeld = false
            handler.postDelayed(holdHome, HOLD_MS)
        } else if (event.action == KeyEvent.ACTION_UP) {
            homeTaken = false
            handler.removeCallbacks(holdHome)
            if (!homeHeld) performGlobalAction(GLOBAL_ACTION_HOME)
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The settings page Close goes through is no app the household opened.
        if (quickSettings?.isClosing == true) return
        // Only an activity is an app coming to the front: TCL's panels over the picture (its settings
        // app's windows, as the sound mode shows one), dialogs and the keyboard leave it as it is.
        if (!isActivity(pkg, event.className)) return
        GlassApp.instance.recents.opened(pkg)
    }

    private fun isActivity(pkg: String, className: CharSequence?): Boolean {
        val name = className?.toString() ?: return false
        return runCatching { packageManager.getActivityInfo(ComponentName(pkg, name), 0) }.isSuccess
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(holdHome)
        if (running === this) running = null
        overlay?.destroy()
        overlay = null
        controlCenter?.destroy()
        controlCenter = null
        quickSettings?.destroy()
        quickSettings = null
        super.onDestroy()
    }

    companion object {
        /** The remote's Settings button: SETTINGS, or NOTIFICATION on the TCL's remote. */
        val CONTROL_CENTER_KEYS = setOf(KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION)

        /** The TCL remote's key for TCL's Quick Settings, the button right of 0 (not one of Android's). */
        private const val TCL_QUICK_SETTINGS = 4514

        /**
         * The quick settings' button. Debug builds also take BOOKMARK, which `tools/remote_keys.py quick`
         * presses on the emulator: no remote there has TCL's key.
         */
        val QUICK_SETTINGS_KEYS = if (BuildConfig.DEBUG) setOf(TCL_QUICK_SETTINGS, KeyEvent.KEYCODE_BOOKMARK) else setOf(TCL_QUICK_SETTINGS)

        /** How long Home is held to open the control center. */
        private const val HOLD_MS = 450L

        /** The service while it is on, for what only it may do (putting the TV to sleep). */
        var running: InputsKeyService? = null
            private set
    }
}
