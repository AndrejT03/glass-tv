package com.andrej.glasstv.remote

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.database.ContentObserver
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.andrej.glasstv.GlassApp
import com.andrej.glasstv.controlcenter.Controls
import com.andrej.glasstv.controlcenter.SystemStats
import com.andrej.glasstv.data.IDLE_MINUTES
import com.andrej.glasstv.data.TvInput
import com.andrej.glasstv.data.tileName
import com.andrej.glasstv.inputs.InputsKeyService
import com.andrej.glasstv.quicksettings.PictureMode
import com.andrej.glasstv.quicksettings.QuickAction
import com.andrej.glasstv.quicksettings.QuickSettingsOverlay
import com.andrej.glasstv.quicksettings.SleepTimer
import com.andrej.glasstv.screensaver.DYNAMIC_ALL
import com.andrej.glasstv.screensaver.SAVERS
import com.andrej.glasstv.screensaver.SUN_LATITUDE
import com.andrej.glasstv.screensaver.SUN_LONGITUDE
import com.andrej.glasstv.screensaver.ShowingSaver
import com.andrej.glasstv.ui.EN
import com.andrej.glasstv.ui.Strings
import com.andrej.glasstv.ui.glass.WALLPAPERS
import com.andrej.glasstv.ui.stringsFor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the iPhone app sees of Glass, and what it may do. The state is Glass's own Compose state, read
 * on the main thread: the TV's screens read the same, so a change from the phone shows on the TV at
 * once, and [RemoteServer] follows it with snapshotFlow. The actions are the ones the remote's
 * buttons run, on the main thread too. Lists the TV defines (picture presets, the light's colours,
 * the settings' choices) come with their names in the TV's language, so the phone shows the TV's
 * own words.
 */
internal class RemoteApi(private val app: GlassApp, private val pairing: Pairing) {
    private val main = Handler(Looper.getMainLooper())

    /** The picture preset and backlight as last read or set: TCL's service doesn't say when they change. */
    private var picture by mutableStateOf(Picture(null, null))

    private data class Picture(val mode: Int?, val backlight: Int?)

    /** TCL's sound mode, followed as it changes (the remote, TCL's own menus, the phone). */
    private var soundModeNow by mutableStateOf(readSoundMode())

    init {
        val uri = Settings.System.getUriFor(TCL_SOUND_MODE)
        app.contentResolver.registerContentObserver(uri, false, object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                soundModeNow = readSoundMode()
            }
        })
    }

    private val arts = ConcurrentHashMap<String, Http.Response>()

    /** The TV's own name for itself ("TCL Smart TV Pro"), when the household hasn't given it one. */
    private val systemName: String by lazy {
        runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    /** The TV's name, as Bonjour and the phone show it: the household's ([Prefs.tvName]) or the system's. */
    val tvName: String get() = app.prefs.tvName.ifBlank { systemName }

    /** Called (on the main thread) when the household renames the TV, for Bonjour to take the new name. */
    var renamed: (() -> Unit)? = null

    fun info(paired: Boolean): JSONObject =
        JSONObject().put("app", "Glass").put("api", API).put("id", pairing.tvId).put("name", tvName).put("paired", paired)

    /** The state, with the volume and the picture read afresh (the remote may have changed them). */
    fun state(): JSONObject {
        readPicture()
        return onMain {
            app.volume.refresh()
            json()
        }
    }

    /** Reads the picture preset and backlight from TCL's service, waiting for the answer (not on the main thread). */
    fun readPicture() {
        if (!app.picture.available) return
        await { done ->
            app.picture.read { mode, backlight ->
                picture = Picture(mode, backlight?.coerceIn(0, 100))
                done()
            }
        }
    }

    /** The state as JSON; main thread only. */
    fun json(): JSONObject {
        val prefs = app.prefs
        val s = stringsFor(prefs.language)
        val light = app.ambient
        val timer = app.sleepTimer
        val front = app.recents.front
        return JSONObject()
            .put("tv", JSONObject().put("id", pairing.tvId).put("name", tvName).put("systemName", systemName).put("language", prefs.language))
            .put(
                "power",
                JSONObject()
                    .put("on", app.screenOn)
                    .put("canSleep", InputsKeyService.running != null)
                    .put("canRestart", InputsKeyService.running != null),
            )
            .put("volume", JSONObject().put("percent", app.volume.percent).put("muted", app.volume.muted))
            .put(
                "picture",
                JSONObject()
                    .put("available", app.picture.available)
                    .putOpt("mode", picture.mode)
                    .putOpt("backlight", picture.backlight)
                    .put("modes", array(PictureMode.entries.mapIndexed { i, m -> option(m.id, s.pictureModes[i]) })),
            )
            .put(
                "sleep",
                JSONObject()
                    .put("minutes", timer.minutes)
                    .put("endsInMs", timer.remaining())
                    .put("choices", array(SleepTimer.CHOICES.mapIndexed { i, m -> option(m, s.sleepChoices[i]) })),
            )
            .put(
                "light",
                JSONObject()
                    .put("on", light.on)
                    .put("color", light.color)
                    .put("link", light.link.name.lowercase())
                    .put("permitted", light.permitted())
                    .put("colors", array((0 until light.count).map { i -> JSONObject().put("label", s.lightColors.getOrElse(i) { "" }).put("swatch", hex(light.swatch(i).toArgb())) }))
                    .putOpt("custom", light.custom?.let(::hex)),
            )
            .put("inputs", array(app.inputs.list.map(::inputJson)))
            .putOpt("currentInput", app.inputs.current().takeIf { front != null && front == app.inputs.playerPackage })
            .put("apps", array(app.apps.visible().map { JSONObject().put("key", it.key).put("label", it.label).put("package", it.packageName) }))
            .put("recent", array(app.recents.list.take(3).map { it.key }))
            .putOpt("lastApp", app.recents.previous()?.key)
            .putOpt("front", front)
            .putOpt(
                "sound",
                soundMode()?.let { mode ->
                    JSONObject().put("mode", mode).put("modes", array(SOUND_MODES.map { option(it, s.soundModes[it]) }))
                },
            )
            .put("saver", JSONObject().put("showing", ShowingSaver.state != null))
            .put("settings", array(settings(s)))
    }

    /** The TV's performance, as the control center's tile shows it (a ping takes up to a second or two). */
    fun stats(): JSONObject {
        val stats = app.stats.sample()
        return JSONObject()
            .put("pingMs", stats.pingMs ?: SystemStats.NO_ANSWER)
            .put("ram", round(stats.ram))
            .put("ramUsedGb", round(stats.ramUsedGb))
            .put("ramTotalGb", round(stats.ramTotalGb))
            .putOpt("downMbps", stats.downMbps?.let(::round))
    }

    /**
     * An app's tile, for the phone's list of apps: the sharp 800x480 art from assets/tiles where the
     * app has one, else the art the launcher drew from its banner.
     */
    fun art(key: String?): Http.Response? {
        val entry = app.apps.all.firstOrNull { it.key == key } ?: return null
        return arts.getOrPut("${entry.key}@${entry.updated}") {
            val designed = runCatching { app.assets.open("tiles/${tileName(entry.packageName)}.webp").use { it.readBytes() } }.getOrNull()
            if (designed != null) {
                Http.Response(200, "image/webp", designed, cacheable = true)
            } else {
                // The launcher keeps its art on the GPU (a hardware bitmap): copied back to compress it.
                val art = entry.art.asAndroidBitmap()
                val readable = if (Build.VERSION.SDK_INT >= 26 && art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
                val out = ByteArrayOutputStream()
                readable.compress(Bitmap.CompressFormat.PNG, 100, out)
                Http.Response(200, "image/png", out.toByteArray(), cacheable = true)
            }
        }
    }

    /** A POST from the phone: does it, and answers with the state after it. */
    fun act(path: String, body: JSONObject): Http.Response = try {
        when (path) {
            "/v1/picture" -> setPicture(body)
            "/v1/power/restart" -> restart()
            "/v1/sound" -> setSoundMode(body)
            else -> onMain { actOnMain(path, body) }
        }
        Http.json(onMain { json() })
    } catch (e: Refused) {
        Http.error(e.status, e.code)
    }

    private fun actOnMain(path: String, body: JSONObject) {
        when (path) {
            "/v1/settings" -> if (!setting(body.getString("key"), body.getString("value"))) refuse(400, "bad-setting")
            "/v1/volume" -> when {
                body.has("percent") -> app.volume.set(body.getInt("percent") / 100f)
                // As many steps (each 1%) as asked: the widgets' buttons go 5 at a time.
                body.has("step") -> body.getInt("step").let { app.volume.step(up = it > 0, count = abs(it).coerceIn(1, MAX_VOLUME_STEPS)) }
                body.has("mute") -> app.volume.mute(body.getBoolean("mute"))
                body.optBoolean("toggleMute") -> app.volume.mute(!app.volume.muted)
                else -> refuse(400, "bad-volume")
            }
            "/v1/sleep" -> app.sleepTimer.set(body.getInt("minutes").coerceIn(0, MAX_SLEEP_MINUTES))
            "/v1/name" -> {
                app.prefs.tvName = body.optString("name").trim().take(MAX_NAME)
                renamed?.invoke()
            }
            "/v1/light" -> light(body)
            "/v1/power/off" -> if (!Controls.sleep()) refuse(409, "needs-service")
            "/v1/power/on" -> wake()
            "/v1/power/toggle" -> if (!app.screenOn) wake() else if (!Controls.sleep()) refuse(409, "needs-service")
            "/v1/inputs/watch" -> app.inputs.watch(app, input(body.getString("id")))
            "/v1/inputs/rename" -> app.inputs.rename(input(body.getString("id")).id, body.optString("name"))
            "/v1/apps/launch" -> launch(body.getString("key"))
            "/v1/apps/last" -> if (!Controls.lastApp(app)) refuse(409, "no-last-app")
            "/v1/saver" -> saver(body.getString("action"))
            "/v1/open" -> if (!open(body.getString("screen"))) refuse(409, "cant-open")
            else -> refuse(404, "unknown")
        }
    }

    /** The settings, as the TV's own settings list has them (HomeController.settingsRows), each a choice. */
    private fun settings(s: Strings): List<JSONObject> {
        val p = app.prefs
        return listOfNotNull(
            choice("homeStyle", s.home, p.homeStyle, listOf("tvos" to s.homeTvos, "clock" to s.homeClock)),
            choice("wallpaper", s.wallpaper, p.wallpaper, WALLPAPERS.map { it.id to it.name }),
            choice("clockStyle", s.clock, p.clockStyle, listOf("glass" to s.clockGlass, "solid" to s.clockSolid)),
            choice("saver", s.screensaver, p.saver, SAVERS.map { it to (s.savers[it] ?: it) }),
            choice("idleMinutes", s.startAfter, p.idleMinutes.toString(), IDLE_MINUTES.map { it.toString() to if (it == 0) s.never else "$it ${s.minutes}" }),
            choice("aerialQuality", s.quality, p.aerialQuality, listOf("hd" to "1080p", "uhd" to "4K")),
            dynamicChoice(s),
            choice("saverClock", s.saverClock, p.saverClock, listOf("big" to s.clockBig, "small" to s.clockSmall, "off" to s.off)),
            choice("sounds", s.sounds, if (p.sounds) "on" else "off", listOf("on" to s.on, "off" to s.off)),
            choice("language", s.language, p.language, listOf("mk" to "Македонски", "en" to "English")),
        )
    }

    private fun setting(key: String, value: String): Boolean {
        val choice = settings(stringsFor(app.prefs.language)).firstOrNull { it.getString("key") == key } ?: return false
        val options = choice.getJSONArray("options")
        if ((0 until options.length()).none { options.getJSONObject(it).getString("id") == value }) return false
        val p = app.prefs
        when (key) {
            "homeStyle" -> p.homeStyle = value
            "wallpaper" -> p.wallpaper = value
            "clockStyle" -> p.clockStyle = value
            "saver" -> p.saver = value
            "idleMinutes" -> p.idleMinutes = value.toInt()
            "aerialQuality" -> p.aerialQuality = value
            "dynamicWallpaper" -> p.dynamicWallpaper = value
            "saverClock" -> p.saverClock = value
            "sounds" -> p.sounds = value == "on"
            "language" -> p.language = value
        }
        return true
    }

    /** The Dynamic Wallpaper saver's picture, once Apple's are on the TV (push_dynamic.sh): one, or all in turn. */
    private fun dynamicChoice(s: Strings): JSONObject? {
        val wallpapers = app.dynamic.list.orEmpty().ifEmpty { return null }
        val value = app.prefs.dynamicWallpaper.takeIf { id -> wallpapers.any { it.id == id } } ?: DYNAMIC_ALL
        return choice("dynamicWallpaper", s.dynamicWallpaper, value, listOf(DYNAMIC_ALL to s.allInTurn) + wallpapers.map { it.id to it.name })
    }

    /**
     * The dynamic desktop pictures as the phone's widgets need them: `dynamic.json` as push_dynamic.sh
     * made it (each picture's frames and the sun's position for each), with where the TV works the
     * sun out from. Read afresh, as the pictures may have been pushed since Glass started.
     */
    fun dynamic(): Http.Response {
        app.dynamic.read()
        val manifest = app.dynamic.manifest()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Http.error(404, "no-dynamic")
        return Http.json(manifest.put("place", JSONObject().put("latitude", SUN_LATITUDE).put("longitude", SUN_LONGITUDE)))
    }

    /** One frame of a dynamic desktop picture, the phone's square of it. */
    fun dynamicFrame(id: String?, frame: String?): Http.Response {
        val file = app.dynamic.phoneFrame(id.orEmpty(), frame?.toIntOrNull() ?: -1) ?: return Http.error(404, "no-frame")
        return Http.Response(200, "image/jpeg", file.readBytes(), cacheable = true)
    }

    private fun setPicture(body: JSONObject) {
        if (!app.picture.available) refuse(409, "no-picture")
        when {
            body.has("mode") -> {
                val mode = body.getInt("mode")
                if (PictureMode.of(mode) == null) refuse(400, "bad-mode")
                await { done ->
                    app.picture.setMode(mode) { now ->
                        picture = picture.copy(mode = now)
                        done()
                    }
                }
            }
            body.has("backlight") -> {
                val value = body.getInt("backlight").coerceIn(0, 100)
                app.picture.setBacklight(value)
                onMain { picture = picture.copy(backlight = value) }
            }
            else -> refuse(400, "bad-picture")
        }
    }

    /**
     * Wakes the TV from its sleep, as the remote's Power does: asleep, the TV keeps Glass and the
     * network up (TCL's network standby, on; seen answering a minute on, 2026-11-01), and a window
     * that turns the screen on wakes it ([WakeActivity]). The state says so once the screen is on.
     */
    private fun wake() {
        if (app.screenOn) return
        if (!WakeActivity.start(app)) refuse(409, "cant-wake")
    }

    /** Restarts the TV through its power menu ([TvButtons]); answered before it goes. */
    private fun restart() {
        val service = InputsKeyService.running ?: refuse(409, "needs-service")
        val pressed = AtomicBoolean(false)
        await(BUTTONS_TIMEOUT_S) { done -> TvButtons(service).restart { pressed.set(it); done() } }
        if (!pressed.get()) refuse(409, "cant-restart")
    }

    /**
     * TCL's sound mode (`tcl_sys_audio_sound_mode`, which TCL's audio service keeps): set by its name
     * as TCL's voice assistant sets it ([TvSound]; the TV shows the names in English, as
     * [EN].soundModes has them). The debug demo keeps a made-up one, for the emulator.
     */
    private fun soundMode(): Int? = if (app.picture.demo) demoSoundMode else soundModeNow

    private fun readSoundMode(): Int? =
        runCatching { Settings.System.getInt(app.contentResolver, TCL_SOUND_MODE, -1) }.getOrDefault(-1).takeIf { it >= 0 }

    private fun setSoundMode(body: JSONObject) {
        val mode = body.getInt("mode")
        if (mode !in SOUND_MODES) refuse(400, "bad-mode")
        if (onMain { soundMode() } == mode) return
        if (app.picture.demo) {
            onMain { demoSoundMode = mode }
            return
        }
        if (!TvSound.set(app, EN.soundModes[mode])) refuse(409, "not-on-tv")
        // TCL notes the new mode in its setting a moment later; then its panel, which shows it, goes.
        val until = SystemClock.uptimeMillis() + SOUND_WAIT_MS
        while (onMain { soundMode() } != mode && SystemClock.uptimeMillis() < until) Thread.sleep(SOUND_LOOK_MS)
        TvSound.close(app)
        if (onMain { soundMode() } != mode) refuse(409, "not-on-tv")
    }

    private var demoSoundMode by mutableStateOf(3)

    private fun light(body: JSONObject) {
        val light = app.ambient
        if (!light.permitted()) refuse(409, "needs-bluetooth")
        when {
            body.has("on") -> light.turn(body.getBoolean("on"))
            body.optBoolean("toggle") -> light.turn(!light.on)
            body.has("color") -> {
                val index = body.getInt("color")
                if (index !in 0 until light.count) refuse(400, "bad-color")
                light.choose(index)
            }
            body.has("rgb") -> light.paint(rgb(body.getString("rgb")))
            body.has("default") -> light.setDefault(rgb(body.getString("default")))
            else -> refuse(400, "bad-light")
        }
    }

    private fun launch(key: String) {
        val entry = app.apps.all.firstOrNull { it.key == key } ?: refuse(404, "no-app")
        app.recents.opened(entry.packageName)
        if (runCatching { app.startActivity(entry.launchIntent()) }.isSuccess) return
        val fallback = app.packageManager.getLeanbackLaunchIntentForPackage(entry.packageName)
            ?: app.packageManager.getLaunchIntentForPackage(entry.packageName)
            ?: refuse(409, "cant-open")
        if (runCatching { app.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) refuse(409, "cant-open")
    }

    /** "toggle" (the iPhone widgets'): stop the one on screen, or start one. */
    private fun saver(action: String) {
        val done = when (action) {
            "start" -> Controls.screensaver(app)
            "toggle" -> ShowingSaver.stop() || Controls.screensaver(app)
            "next" -> ShowingSaver.skip(1)
            "previous" -> ShowingSaver.skip(-1)
            "stop" -> ShowingSaver.stop()
            else -> refuse(400, "bad-action")
        }
        if (!done) refuse(409, if (action == "start" || action == "toggle") "cant-start" else "no-saver")
    }

    /** One of the TV's own screens, as the control center and the quick settings open them. */
    private fun open(screen: String): Boolean = when (screen) {
        "settings" -> Controls.systemSettings(app)
        "wifi" -> Controls.wifiSettings(app)
        "bluetooth" -> Controls.bluetoothSettings(app)
        "soundMode" -> QuickSettingsOverlay.run(app, QuickAction.Sound)
        "speakers" -> QuickSettingsOverlay.run(app, QuickAction.Speakers)
        "picture" -> QuickSettingsOverlay.run(app, QuickAction.PictureSettings)
        "gameMaster" -> QuickSettingsOverlay.run(app, QuickAction.GameMaster)
        "cec" -> QuickSettingsOverlay.run(app, QuickAction.Cec)
        "controlCenter" -> menu(InputsKeyService.Menu.ControlCenter)
        "inputs" -> menu(InputsKeyService.Menu.Inputs)
        "quickSettings" -> menu(InputsKeyService.Menu.QuickSettings)
        else -> refuse(400, "bad-screen")
    }

    /** One of Glass's own menus on the TV, opened (or closed) as its remote button does. */
    private fun menu(which: InputsKeyService.Menu): Boolean =
        (InputsKeyService.running ?: refuse(409, "needs-service")).toggle(which)

    private fun input(id: String): TvInput = app.inputs.list.firstOrNull { it.id == id } ?: refuse(404, "no-input")

    private fun inputJson(i: TvInput) = JSONObject()
        .put("id", i.id)
        .put("name", i.name)
        .putOpt("port", i.port)
        .put("kind", i.kind.name.lowercase())
        .put("connected", i.connected)

    private fun choice(key: String, title: String, value: String, options: List<Pair<String, String>>) = JSONObject()
        .put("key", key)
        .put("title", title)
        .put("value", value)
        .put("options", array(options.map { (id, label) -> option(id, label) }))

    private fun option(id: Any, label: String) = JSONObject().put("id", id).put("label", label)

    private fun array(items: List<Any>) = JSONArray(items)

    private fun hex(argb: Int) = "%06X".format(argb and 0xFFFFFF)

    private fun rgb(hex: String): Int = hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) ?: refuse(400, "bad-rgb")

    private fun round(value: Float) = (value * 100f).roundToInt() / 100.0

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(Callable { block() })
        main.post(task)
        return try {
            task.get(MAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Starts something that answers on the main thread later, and waits for it (a while at most). */
    private fun await(timeoutS: Long = MAIN_TIMEOUT_S, start: (done: () -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        main.post { start { latch.countDown() } }
        latch.await(timeoutS, TimeUnit.SECONDS)
    }

    private class Refused(val status: Int, val code: String) : Exception(code)

    private fun refuse(status: Int, code: String): Nothing = throw Refused(status, code)

    companion object {
        /** The version of this API, for a phone app newer or older than the TV's Glass. */
        const val API = 1

        private const val MAIN_TIMEOUT_S = 3L
        private const val BUTTONS_TIMEOUT_S = 8L
        private const val SOUND_WAIT_MS = 3_000L
        private const val SOUND_LOOK_MS = 100L

        /** Where TCL's audio service keeps the sound mode (QuickSettingsOverlay reads it too). */
        private const val TCL_SOUND_MODE = "tcl_sys_audio_sound_mode"

        /** The sound modes this TV's pop-up offers, in its order. */
        private val SOUND_MODES = TvSound.MODES
        private const val MAX_SLEEP_MINUTES = 240
        private const val MAX_VOLUME_STEPS = 20
        private const val MAX_NAME = 40
    }
}
