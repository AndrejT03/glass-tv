package com.andrej.glasstv.quicksettings

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.andrej.glasstv.data.Prefs

/**
 * The TCL's picture preset and backlight, which its own Quick Settings set: through
 * `tcl_tv_display`, the service of TCL's picture-quality library (com.tcl.tv.display), which the
 * TV's SELinux policy lets any app find and call (`allow untrusted_app tcl_tv_display_service`,
 * `allow untrusted_app tcl_display_service:binder call`). The calls are the ones TCL's settings app
 * makes, read out of its code (TvSettings 3.30.02, `PictureManager2`): the same transaction codes,
 * the same arguments, so the TV does exactly what its own menu would.
 *
 * Everything runs on a thread of its own (a binder call into the TV's picture hardware can take a
 * while), and the answers come back on the main thread. Where there is no such service (the
 * emulator, any other TV) it is [available] = false, and [demo] stands in with made-up values.
 *
 * TCL keeps a backlight of each preset's own (on this TV Standard 60, Vivid 100), so the backlight
 * jumped whenever the preset changed (Vivid, an HDR picture). The household's, once set from Glass
 * (`Prefs.backlight`), is kept: given back to the TV after a preset Glass sets, and whenever it is
 * read and the TV has another.
 */
class TvPicture(private val prefs: Prefs) {
    private val thread = HandlerThread("tv-picture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    /** Debug: made-up values on a TV without TCL's service (the emulator). */
    var demo = false
    private var demoMode = VIVID
    private var demoBacklight = 60

    private val service: IBinder? by lazy {
        runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, SERVICE) as IBinder?
        }.onFailure { Log.w(TAG, "no $SERVICE", it) }.getOrNull()
    }

    /** TCL's service is there (or the demo stands in for it). */
    val available: Boolean get() = demo || service != null

    /** The backlight the household last set from Glass (0..100), or -1: TCL's own for each preset till then. */
    @Volatile
    private var kept = prefs.backlight

    /**
     * Reads the picture preset (a mode number, [PictureMode]) and the backlight (0..100); null for
     * what can't be read. A backlight other than the household's is set back to theirs first.
     */
    fun read(done: (mode: Int?, backlight: Int?) -> Unit) = handler.post {
        val mode = if (demo) demoMode else call(GET_PICTURE_MODE)
        var backlight = if (demo) demoBacklight else call(GET_BACKLIGHT)
        val kept = kept
        if (backlight != null && kept >= 0 && backlight != kept) backlight = applyBacklight(kept)
        main.post { done(mode, backlight) }
    }

    /**
     * Reads Game Master on an HDMI port ([source], one of TCL's EnInputSource: [hdmi]): TCL keeps it
     * per port, 2 Auto, 1 On, 0 Off (TvSettings shows 2 minus it as its place among Auto, On and Off).
     * Null where it can't be read.
     */
    fun readGameMode(source: Int?, done: (Int?) -> Unit) = handler.post {
        val mode = when {
            source == null -> null
            demo -> 2
            else -> call(GET_GAME_MODE_CONFIG, source)
        }
        main.post { done(mode) }
    }

    /**
     * Sets the picture preset as TCL's settings do: `setPictureModeByType(window 0, EN_APPLY_CURRENT,
     * mode, EN_PICTURE_MODE_FIELD_NORMAL, EN_TCL_ACT_EXEC_SAVE)`, then the household's backlight in it.
     * [done] gets the preset the TV is on afterwards, read back.
     */
    fun setMode(mode: Int, done: (Int?) -> Unit) = handler.post {
        val now = if (demo) {
            demoMode = mode
            mode
        } else {
            call(SET_PICTURE_MODE_BY_TYPE, 0, APPLY_CURRENT, mode, FIELD_NORMAL, EXEC_SAVE)
            call(GET_PICTURE_MODE)
        }
        kept.takeIf { it >= 0 }?.let(::applyBacklight)
        main.post { done(now) }
    }

    /**
     * Sets the backlight (0..100), as the household's from now on. Presses in quick succession only
     * send the last value, as the hardware takes its time.
     */
    fun setBacklight(value: Int) {
        kept = value
        main.post { prefs.backlight = value }
        handler.removeCallbacks(pendingBacklight)
        backlight = value
        handler.post(pendingBacklight)
    }

    private var backlight = 0
    private val pendingBacklight = Runnable { applyBacklight(backlight) }

    /**
     * `setBacklight(EN_APPLY_CURRENT, value, EN_TCL_ACT_EXEC_SAVE)`, as TCL's settings set it (its
     * EN_APPLY_ALL_PICMODE leaves the other presets' as they are too): the backlight the TV has then.
     */
    private fun applyBacklight(value: Int): Int? {
        if (demo) {
            demoBacklight = value
            return value
        }
        call(SET_BACKLIGHT, APPLY_CURRENT, value, EXEC_SAVE)
        return call(GET_BACKLIGHT)
    }

    /** One call into TCL's service: its interface token and [args], an int back (null if it failed). */
    private fun call(code: Int, vararg args: Int): Int? {
        val binder = service ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            args.forEach(data::writeInt)
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            Log.w(TAG, "call $code failed", e)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        /** TCL's EnInputSource for HDMI [port] (1..4), as its picture service takes it. */
        fun hdmi(port: Int) = 7 + port

        private const val TAG = "TvPicture"
        private const val SERVICE = "tcl_tv_display"
        private const val DESCRIPTOR = "com.tcl.tv.display.ITvDisplayManagerService"

        // ITvDisplayManagerService's transaction codes (com.tcl.tv.display.jar on the TV).
        private const val GET_PICTURE_MODE = 27
        private const val SET_BACKLIGHT = 100
        private const val GET_BACKLIGHT = 101
        private const val GET_GAME_MODE_CONFIG = 160
        private const val SET_PICTURE_MODE_BY_TYPE = 182

        // Ordinals of TCL's EnApplyType, EnPicModeFieldType and EnTclActType.
        private const val APPLY_CURRENT = 0
        private const val FIELD_NORMAL = 2
        private const val EXEC_SAVE = 2

        private const val VIVID = 1
    }
}

/**
 * The picture presets the TCL offers for ordinary (SDR) pictures, in the order its own menu lists
 * them (TvSettings' `getPlainDefault`), with the mode numbers its picture service uses for them
 * (`PictureManager2.getPictureMode`'s table). HDR, HLG and Dolby Vision pictures have presets of
 * their own, which TCL's own picture menu handles.
 */
enum class PictureMode(val id: Int) {
    Intelligent(10),
    Vivid(1),
    Standard(0),
    Sport(4),
    Filmmaker(60),
    Movie(3),
    ;

    companion object {
        fun of(id: Int?) = entries.firstOrNull { it.id == id }
    }
}
