package com.andrej.glasstv.controlcenter

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * A tile of the control center, on its grid of four columns: [col] and [row] of its top-left cell,
 * [w] x [h] cells. Power Off and Performance are big squares side by side, then two rows of
 * capsules (the ambient light and its colour, the sound and Wi-Fi) and a row of round buttons, as in
 * tvOS 26.
 */
enum class Tile(val col: Int, val row: Int, val w: Int, val h: Int) {
    Power(0, 0, 2, 2),
    Performance(2, 0, 2, 2),
    Ambient(0, 2, 2, 1),
    AmbientColor(2, 2, 2, 1),
    Sound(0, 3, 2, 1),
    Wifi(2, 3, 2, 1),
    Bluetooth(0, 4, 1, 1),
    Screensaver(1, 4, 1, 1),
    LastApp(2, 4, 1, 1),
    Settings(3, 4, 1, 1),
    ;

    val round get() = w == 1 && h == 1
}

enum class Step { Left, Right, Up, Down }

/**
 * Where the remote is in the control center. Shared by the one on the home screen and the one the
 * remote's Home button (held) opens over any app.
 */
@Stable
class ControlCenterModel(private val volume: Volume, val ambient: AmbientLight) {
    var focus by mutableStateOf(Tile.Power)
        private set

    /**
     * OK on Sound or the light's colour: Left and Right change the volume or the colour until OK (or
     * any other arrow, or Back).
     */
    var adjusting by mutableStateOf(false)
        private set

    /** A note under the tiles, when a tile can't do its job (Power Off without the accessibility service). */
    var notice by mutableStateOf<String?>(null)

    fun reset() {
        focus = Tile.Power
        adjusting = false
        notice = null
    }

    /**
     * An arrow: the next tile that way (true), or the volume or the light's colour while adjusting
     * it. False when there is no tile that way, or the volume is at its end.
     */
    fun move(step: Step): Boolean {
        if (adjusting) {
            if (step == Step.Left || step == Step.Right) {
                if (focus == Tile.AmbientColor) {
                    ambient.step(if (step == Step.Right) 1 else -1)
                    return true
                }
                val before = volume.level
                volume.step(up = step == Step.Right)
                return volume.level != before
            }
            adjusting = false
        }
        val next = neighbour(focus, step) ?: return false
        focus = next
        notice = null
        return true
    }

    /**
     * OK on the tile in focus: the tile whose action the host runs (and closes the control center
     * for), or null for one that does its job in place (the light, the volume, the colour) or has
     * none (Performance).
     */
    fun select(): Tile? = when (focus) {
        Tile.Sound -> {
            adjusting = !adjusting
            null
        }
        Tile.AmbientColor -> {
            adjusting = !adjusting
            if (adjusting) ambient.apply()
            null
        }
        Tile.Ambient -> {
            ambient.toggle()
            null
        }
        Tile.Performance -> null
        else -> focus
    }

    /** Back: out of adjusting the volume first; false when the control center should close. */
    fun back(): Boolean {
        if (!adjusting) return false
        adjusting = false
        return true
    }

    private companion object {
        /**
         * The tile beyond [from] in the direction of [step]: the nearest whose span overlaps it
         * across the move (from a round button up to the capsule above it), else the nearest.
         */
        fun neighbour(from: Tile, step: Step): Tile? {
            val ahead = Tile.entries.filter { t ->
                when (step) {
                    Step.Left -> t.col + t.w <= from.col
                    Step.Right -> t.col >= from.col + from.w
                    Step.Up -> t.row + t.h <= from.row
                    Step.Down -> t.row >= from.row + from.h
                }
            }
            fun gap(t: Tile) = when (step) {
                Step.Left -> from.col - (t.col + t.w)
                Step.Right -> t.col - (from.col + from.w)
                Step.Up -> from.row - (t.row + t.h)
                Step.Down -> t.row - (from.row + from.h)
            }
            fun overlaps(t: Tile) = when (step) {
                Step.Left, Step.Right -> t.row < from.row + from.h && from.row < t.row + t.h
                Step.Up, Step.Down -> t.col < from.col + from.w && from.col < t.col + t.w
            }
            // Centres across the move, in half cells; the left or upper one wins a tie.
            fun across(t: Tile) = when (step) {
                Step.Left, Step.Right -> abs((2 * t.row + t.h) - (2 * from.row + from.h))
                Step.Up, Step.Down -> abs((2 * t.col + t.w) - (2 * from.col + from.w))
            }
            val pool = ahead.filter(::overlaps).ifEmpty { ahead }
            return pool.minWithOrNull(compareBy<Tile>({ gap(it) }, { across(it) }, { it.ordinal }))
        }
    }
}
