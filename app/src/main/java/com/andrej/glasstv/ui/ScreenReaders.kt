package com.andrej.glasstv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Leaves the semantics tree under this element empty unless a screen reader is on.
 *
 * Glass's own accessibility service (the Input button, Home held) is all it takes for Compose to
 * treat accessibility as on, and it then walked the screen's whole semantics tree every 100 ms
 * while anything moved: 2–8 ms of the TV's main thread each time, in the middle of animations.
 * Only a screen reader (spoken or braille feedback, touch exploration) reads the launcher's content.
 */
@Composable
fun Modifier.semanticsForScreenReaders(): Modifier {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var reader by remember(manager) { mutableStateOf(manager.hasScreenReader()) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val state = AccessibilityManager.AccessibilityStateChangeListener { reader = manager.hasScreenReader() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { reader = manager.hasScreenReader() }
        manager.addAccessibilityStateChangeListener(state)
        manager.addTouchExplorationStateChangeListener(touch)
        onDispose {
            manager.removeAccessibilityStateChangeListener(state)
            manager.removeTouchExplorationStateChangeListener(touch)
        }
    }
    return if (reader) this else clearAndSetSemantics { }
}

private fun AccessibilityManager?.hasScreenReader(): Boolean {
    if (this == null || !isEnabled) return false
    if (isTouchExplorationEnabled) return true
    val feedback = AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE
    return getEnabledAccessibilityServiceList(feedback).isNotEmpty()
}
