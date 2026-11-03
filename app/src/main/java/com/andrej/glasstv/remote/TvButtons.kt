package com.andrej.glasstv.remote

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * What only the TV's own menus may do, done from the iPhone app by pressing their buttons as the
 * remote would, through Glass's accessibility service: restarting the TV (Android keeps rebooting
 * to the system; its power menu has Restart). The service finds a button by its label in the windows
 * on screen and clicks it; the menu shows for a moment on the TV, as it would with the remote.
 */
class TvButtons(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())

    /** Opens the power menu (as holding the remote's Power does) and presses Restart. */
    fun restart(done: (Boolean) -> Unit) {
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) return done(false)
        press(::isRestart, MENU_WAIT_MS) { pressed ->
            if (!pressed) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return@press done(false)
            }
            // Answered now: the TV may be gone a moment later. Some menus ask once more.
            done(true)
            press(::isRestart, CONFIRM_WAIT_MS) { }
        }
    }

    /** Looks for the button every few frames until [timeoutMs], as a menu takes a moment to come up. */
    private fun press(matches: (String) -> Boolean, timeoutMs: Long, done: (Boolean) -> Unit) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        handler.post(object : Runnable {
            override fun run() {
                val node = find(matches)
                when {
                    node != null -> done(click(node))
                    SystemClock.uptimeMillis() > until -> done(false)
                    else -> handler.postDelayed(this, LOOK_EVERY_MS)
                }
            }
        })
    }

    /** The first node whose text (or description) [matches], in any window but Glass's own. */
    private fun find(matches: (String) -> Boolean): AccessibilityNodeInfo? {
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList()) +
            listOfNotNull(service.rootInActiveWindow)
        return roots.filter { it.packageName != service.packageName }.firstNotNullOfOrNull { search(it, matches) }
    }

    private fun search(node: AccessibilityNodeInfo, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        val label = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!label.isNullOrEmpty() && matches(label)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            search(child, matches)?.let { return it }
        }
        return null
    }

    /**
     * Clicks what the label names: the label itself or an ancestor when they take clicks, else the
     * control beside it in its row (TCL's pop-up: the mode's name is plain text, its radio button takes
     * the click). Never looks inside a whole list, where the first control would be another row's.
     */
    private fun click(label: AccessibilityNodeInfo): Boolean {
        var row: AccessibilityNodeInfo? = label
        while (row != null && row.collectionInfo == null) {
            if (row.isClickable) return row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickableIn(row)?.let { return it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            row = row.parent
        }
        return false
    }

    private fun clickableIn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable) return child
            clickableIn(child)?.let { return it }
        }
        return null
    }

    private fun isRestart(label: String): Boolean {
        val l = label.lowercase()
        return l.startsWith("restart") || l.startsWith("reboot") || l.startsWith("рестарт")
    }

    private companion object {
        const val MENU_WAIT_MS = 3_000L
        const val CONFIRM_WAIT_MS = 1_500L
        const val LOOK_EVERY_MS = 120L
    }
}
