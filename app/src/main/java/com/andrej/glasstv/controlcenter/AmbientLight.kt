package com.andrej.glasstv.controlcenter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.andrej.glasstv.data.Prefs
import java.util.UUID

/**
 * The ambient light behind the TV: a Bluetooth LED strip (duoCo's ELK-BLEDDM), the one Ambient Sync
 * turns on with the TV and off a few minutes after it. Glass speaks the strip's own protocol, as
 * Ambient Sync does: 9-byte commands written to its characteristic FFF3. The strip takes one
 * connection at a time, so Glass connects when the control center opens (a press then acts at
 * once) and lets go shortly after it closes, leaving the strip to Ambient Sync.
 *
 * The strip can't be asked what it is doing. It is on whenever the TV is (Ambient Sync sees to that),
 * so the tile starts from on each time the screen comes on, and shows what Glass told it after that.
 * Nor does it keep its colour when it is turned off and on over Bluetooth while it has power (on
 * 2026-10-28 that lost the household's sky blue): so Glass, the one that knows the colour, gives it
 * the colour again with every turn-on. Ambient Sync's own turn-on carries no colour, so when Glass
 * turned the strip off before the TV went to sleep, Glass gives it its colour again once the TV is
 * back on and Ambient Sync has had the strip.
 *
 * When the screen goes off Glass lets the strip go at once, even with the control center still open
 * (the remote's Power button never reaches it): holding on through standby would keep Ambient Sync
 * from turning the strip off, and on again with the TV.
 */
@Stable
class AmbientLight(private val context: Context, private val prefs: Prefs) {
    enum class Link { Idle, Connecting, Ready, Unreachable, NoPermission }

    var on by mutableStateOf(true)
        private set

    /**
     * A colour of the household's own (0xRRGGBB), chosen on the iPhone app's colour wheel: once there
     * is one, it is the last of the tile's colours ([CUSTOM]), after [COLORS].
     */
    var custom by mutableStateOf(prefs.ambientCustom.takeIf { it >= 0 })
        private set

    /** How many colours the tile steps through: [COLORS], and the household's own once there is one. */
    val count: Int get() = if (custom != null) COLORS.size + 1 else COLORS.size

    /**
     * Index of the colour Glass last set: in [COLORS], 0 being the strip's own ([Prefs.ambientDefault]),
     * or [CUSTOM].
     */
    var color by mutableIntStateOf(prefs.ambientColor.coerceIn(0, count - 1))
        private set

    var link by mutableStateOf(Link.Idle)
        private set

    /** What the colour at [index] looks like on screen (the strip's own default from [Prefs]). */
    fun swatch(index: Int): Color = when (index) {
        0 -> Color(prefs.ambientDefault or OPAQUE)
        CUSTOM -> Color((custom ?: prefs.ambientDefault) or OPAQUE)
        else -> COLORS[index].swatch
    }

    private val thread = HandlerThread("ambient").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // On the ambient thread only.
    private var gatt: BluetoothGatt? = null
    private var target: BluetoothGattCharacteristic? = null
    private var writing = false
    private var wanted = false
    private var power: ByteArray? = null
    private var paint: ByteArray? = null
    private var retried = false
    private var awake = true
    private var wakeTries = 0

    /** The control center opened: connect now, so a press acts at once. */
    fun open() {
        handler.post {
            wanted = true
            handler.removeCallbacks(letGo)
            connect()
        }
    }

    /** The control center closed: let the strip go once what was sent has gone. */
    fun close() {
        handler.post {
            wanted = false
            lingerIfDone()
        }
    }

    /** The screen came on, and with it the strip (Ambient Sync's doing). */
    fun screenOn() {
        main.post { on = true }
        handler.post {
            awake = true
            wakeTries = 0
            handler.removeCallbacks(wakePaint)
            if (prefs.ambientOff) handler.postDelayed(wakePaint, WAKE_PAINT_MS)
        }
    }

    /** The screen went off: the strip goes back to Ambient Sync now (once what was sent has gone). */
    fun screenOff() {
        handler.post {
            awake = false
            wanted = false
            handler.removeCallbacks(wakePaint)
            if (gatt != null && !writing && power == null && paint == null) {
                Log.i(TAG, "Screen off: strip let go")
                disconnect(Link.Idle)
            }
        }
    }

    /** Back from standby after Glass had turned the strip off: on, in Glass's colour. */
    private val wakePaint = Runnable {
        if (!awake || !prefs.ambientOff || !permitted()) return@Runnable
        wakeTries++
        Log.i(TAG, "Screen on: giving the strip its colour (try $wakeTries)")
        power = ON
        paint = colorCommand(currentRgb())
        send()
    }

    fun toggle() = turn(!on)

    /**
     * Turns the light on (in its colour) or off. Sent even when the tile already says so: the strip
     * can't be asked, so a phone saying "on" makes sure of it.
     */
    fun turn(on: Boolean) {
        // Without Bluetooth allowed nothing would reach the strip: the tile keeps saying what it is.
        if (!permitted()) {
            report(Link.NoPermission)
            return
        }
        this.on = on
        val command = if (on) ON else OFF
        val rgb = currentRgb()
        handler.post {
            power = command
            if (command === ON) paint = colorCommand(rgb)
            send()
        }
    }

    /** The colour tile opened for changing: the strip shows the colour the tile does (when it is on). */
    fun apply() {
        if (!permitted()) {
            report(Link.NoPermission)
            return
        }
        if (!on) return
        val rgb = currentRgb()
        handler.post {
            paint = colorCommand(rgb)
            send()
        }
    }

    /** The strip's own colour, Default's from now on: the strip turns it (and on) at once. */
    fun setDefault(rgb: Int) {
        prefs.ambientDefault = rgb and 0xFFFFFF
        prefs.ambientColor = 0
        color = 0
        on = true
        val command = colorCommand(rgb)
        handler.post {
            power = ON
            paint = command
            send()
        }
    }

    private fun currentRgb() = when (color) {
        0 -> prefs.ambientDefault
        CUSTOM -> custom ?: prefs.ambientDefault
        else -> COLORS[color].rgb
    }

    /** The colour at [index] (and the light on, if it was off). */
    fun choose(index: Int) = step(index - color)

    /** A colour of the household's own, from the iPhone app (and the light on, if it was off). */
    fun paint(rgb: Int) {
        if (!permitted()) {
            report(Link.NoPermission)
            return
        }
        custom = rgb and 0xFFFFFF
        prefs.ambientCustom = rgb and 0xFFFFFF
        choose(CUSTOM)
    }

    /** The next or previous colour (and the light on, if it was off). */
    fun step(delta: Int) {
        if (!permitted()) {
            report(Link.NoPermission)
            return
        }
        color = (color + delta).mod(count)
        prefs.ambientColor = color
        val rgb = currentRgb()
        val turnOn = !on
        on = true
        handler.post {
            if (turnOn) power = ON
            // Only the latest colour is sent: stepping quickly past some never waits for them.
            paint = colorCommand(rgb)
            send()
        }
    }

    private fun send() {
        handler.removeCallbacks(letGo)
        retried = false
        if (gatt == null) connect() else flush()
    }

    @SuppressLint("MissingPermission") // permitted() checks it
    private fun connect() {
        if (gatt != null) return
        if (!permitted()) {
            report(Link.NoPermission)
            dropPending()
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = runCatching { adapter?.takeIf { it.isEnabled }?.getRemoteDevice(prefs.ambientStrip) }.getOrNull()
        if (device == null) {
            report(Link.Unreachable)
            dropPending()
            wakeAgain()
            return
        }
        report(Link.Connecting)
        gatt = runCatching {
            // Deprecated only for Android 16's replacement; the TV runs 12.
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            report(Link.Unreachable)
            dropPending()
            wakeAgain()
            return
        }
        handler.postDelayed(gaveUp, CONNECT_TIMEOUT_MS)
    }

    // Called on a Bluetooth thread: each goes over to the ambient thread, where the rest runs.
    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> g.discoverServices()
                    newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS -> lost()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { discovered(g) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                writing = false
                flush()
            }
        }
    }

    private fun discovered(g: BluetoothGatt) {
        if (g !== gatt) return
        target = g.services.firstNotNullOfOrNull { it.getCharacteristic(WRITE_UUID) }
        if (target == null) {
            lost()
            return
        }
        handler.removeCallbacks(gaveUp)
        retried = false
        report(Link.Ready)
        flush()
    }

    /** Writes the power command, then the colour, one at a time. */
    @SuppressLint("MissingPermission")
    private fun flush() {
        val g = gatt ?: return
        val c = target ?: return
        if (writing) return
        val isPower = power != null
        val next = power ?: paint
        if (next == null) {
            lingerIfDone()
            return
        }
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        writing = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, next, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = next
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        when {
            !writing -> handler.postDelayed({ flush() }, 60) // the stack is still busy: again in a moment
            isPower -> {
                power = null
                prefs.ambientOff = next === OFF
            }
            else -> paint = null
        }
    }

    private fun lingerIfDone() {
        if (!wanted && !writing && power == null && paint == null) handler.postDelayed(letGo, LINGER_MS)
    }

    private val letGo = Runnable { disconnect(Link.Idle) }

    /** Connecting took too long (the strip unpowered, or Ambient Sync connected to it just then). */
    private val gaveUp = Runnable { lost() }

    private fun lost() {
        handler.removeCallbacks(gaveUp)
        val pending = power != null || paint != null
        disconnect(Link.Unreachable)
        // Once more, a moment later, for a press that hasn't reached the strip yet.
        if (pending && !retried) {
            retried = true
            handler.postDelayed({ connect() }, RETRY_MS)
        } else {
            dropPending()
            if (!wanted) report(Link.Unreachable)
            wakeAgain()
        }
    }

    /** The colour after standby didn't reach the strip: Ambient Sync may still have it, or Bluetooth be off yet. */
    private fun wakeAgain() {
        if (!awake || !prefs.ambientOff || wakeTries !in 1 until WAKE_TRIES) return
        handler.removeCallbacks(wakePaint)
        handler.postDelayed(wakePaint, WAKE_RETRY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun disconnect(state: Link) {
        handler.removeCallbacks(gaveUp)
        handler.removeCallbacks(letGo)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        target = null
        writing = false
        report(state)
    }

    private fun dropPending() {
        power = null
        paint = null
    }

    private fun report(value: Link) {
        main.post { link = value }
    }

    /** Glass may use Bluetooth (on Android 12 and later, once the user allows it). */
    fun permitted(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** A colour of the tile: the RGB sent to the strip, and how it looks on screen. */
    class Choice(val rgb: Int, val swatch: Color)

    companion object {
        /** The strip's write characteristic (in its FFF0 service). */
        private val WRITE_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")

        // ELK-BLEDDM's power commands (Ambient Sync's, for this strip), and the ELK family's colour.
        private val ON = bytes(0x7e, 0x04, 0x04, 0xf0, 0x00, 0x01, 0xff, 0x00, 0xef)
        private val OFF = bytes(0x7e, 0x04, 0x04, 0x00, 0x00, 0x00, 0xff, 0x00, 0xef)

        private fun colorCommand(rgb: Int) =
            bytes(0x7e, 0x00, 0x05, 0x03, rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff, 0x00, 0xef)

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        private const val OPAQUE = 0xFF000000.toInt()
        private const val TAG = "AmbientLight"
        // Connecting has taken 0.25 s, 4.1 s and 6.0 s (2026-10-31, given up on that very moment); a
        // try cut off mid-way leaves the strip busy for a while (GATT error 133 a second later).
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val RETRY_MS = 3_000L

        /** After the screen comes on, time for Ambient Sync to connect, turn the strip on and let go. */
        private const val WAKE_PAINT_MS = 10_000L
        private const val WAKE_RETRY_MS = 10_000L
        private const val WAKE_TRIES = 4

        /** How long the strip stays Glass's after the control center closes, for opening it again. */
        private const val LINGER_MS = 4_000L

        /**
         * The colours the tile steps through, the strip's own first. Pure colours for the strip (it
         * mixes its LEDs, so on it they look as they are named); the swatches are Apple's system colours.
         */
        val COLORS = listOf(
            Choice(0, Color.White), // the strip's own, from Prefs
            Choice(0xFF8C2A, Color(0xFFFFC98A)), // warm white
            Choice(0xFFFFFF, Color(0xFFF2F2F7)), // white
            Choice(0xFF0000, Color(0xFFFF453A)), // red
            Choice(0xFF4000, Color(0xFFFF9F0A)), // orange
            Choice(0xFFB000, Color(0xFFFFD60A)), // yellow
            Choice(0x00FF00, Color(0xFF30D158)), // green
            Choice(0x00FFC0, Color(0xFF63E6E2)), // mint
            Choice(0x0000FF, Color(0xFF0A84FF)), // blue
            Choice(0x8000FF, Color(0xFFBF5AF2)), // purple
            Choice(0xFF0060, Color(0xFFFF375F)), // pink
        )

        /** Index of the household's own colour ([custom]), after [COLORS]. */
        val CUSTOM = COLORS.size
    }
}
