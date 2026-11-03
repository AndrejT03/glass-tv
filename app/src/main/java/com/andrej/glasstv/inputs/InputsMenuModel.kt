package com.andrej.glasstv.inputs

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.data.TvInput

/**
 * A row of the inputs menu: an input, a recently opened app, or More, which unfolds the inputs with
 * nothing plugged in and the apps.
 */
sealed interface InputRow {
    data class Input(val input: TvInput) : InputRow
    data class App(val app: AppEntry) : InputRow
    data object More : InputRow
}

/**
 * What the inputs menu shows and where the remote is in it. Shared by the menu on the home screen
 * and the one the remote's Input button opens over any app.
 */
@Stable
class InputsMenuModel(private val inputs: () -> List<TvInput>, private val recents: () -> List<AppEntry>) {
    var index by mutableIntStateOf(0)
        private set

    /** More has been chosen: every input is listed, and the recent apps after them. */
    var expanded by mutableStateOf(false)
        private set

    /**
     * The inputs with a device on them, then More for the rest (the inputs with nothing plugged in,
     * and the three apps opened last); everything once More has been chosen.
     */
    fun rows(): List<InputRow> {
        val all = inputs()
        val apps = recents().take(RECENT_APPS)
        if (expanded) return all.map { InputRow.Input(it) } + apps.map { InputRow.App(it) }
        val plugged = all.filter { it.connected }.ifEmpty { all }
        val behind = plugged.size < all.size || apps.isNotEmpty()
        return plugged.map { InputRow.Input(it) } + if (behind) listOf(InputRow.More) else emptyList()
    }

    fun reset() {
        expanded = false
        index = 0
    }

    /** One row up or down; false at either end. */
    fun move(step: Int): Boolean {
        val next = index + step
        if (next !in rows().indices) return false
        index = next
        return true
    }

    /**
     * OK on the row in focus: the input to switch to or the app to open, or null when it was More,
     * which has now unfolded the rest.
     */
    fun select(): InputRow? = when (val row = rows().getOrNull(index)) {
        is InputRow.Input, is InputRow.App -> row
        InputRow.More -> {
            val shown = rows().toSet()
            expanded = true
            // Onto the first of the rows that has just unfolded.
            index = rows().indexOfFirst { it !in shown }.coerceAtLeast(0)
            null
        }
        null -> null
    }

    private companion object {
        const val RECENT_APPS = 3
    }
}
