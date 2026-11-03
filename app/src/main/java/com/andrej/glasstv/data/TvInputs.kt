package com.andrej.glasstv.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What kind of socket an input is; the inputs menu draws its icon from it. Listed in this order. */
enum class InputKind { Tuner, Hdmi, Composite, Other }

/**
 * One of the TV's inputs. [name] is what the user knows it by: their own label for it, the device
 * on it (HDMI-CEC: "PlayStation 5"), or the port ("HDMI 1"); [port] is the port when [name] is one
 * of the others. [connected]: a device is plugged in (the aerial always counts as one).
 */
@Immutable
data class TvInput(val id: String, val name: String, val port: String?, val kind: InputKind, val connected: Boolean)

/**
 * The TV's inputs (its HDMI ports and the aerial), as TvInputManager lists them, kept up to date as
 * devices come and go, under the names the household gives them in [Prefs.inputNames].
 */
class TvInputs(private val context: Context, private val prefs: Prefs) {
    var list by mutableStateOf<List<TvInput>>(emptyList())
        private set

    /** Debug: made-up inputs, to see the menu on an emulator, which has none. */
    var demo = false
        set(value) {
            field = value
            reload()
        }

    private val manager: TvInputManager? = runCatching { context.getSystemService(TvInputManager::class.java) }.getOrNull()

    private val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = reload()
        override fun onInputAdded(inputId: String) = reload()
        override fun onInputRemoved(inputId: String) = reload()
        override fun onInputUpdated(inputId: String) = reload()
        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = reload()
    }

    /** The TV's own player, which shows the inputs: opening it is switching inputs, not opening an app. */
    val playerPackage: String? by lazy {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput("probe"), TvContract.Channels.CONTENT_ITEM_TYPE)
        runCatching { context.packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName }.getOrNull()
    }

    fun start() {
        manager?.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    /**
     * Switches the TV to [input] in its own player: an HDMI port through its passthrough channel,
     * the aerial from its channels. The type goes along with the address: on the TCL a bare
     * content:// address goes to its web browser, while a channel goes to its TV player. Where the
     * player takes neither, the TV's own inputs panel opens.
     */
    fun watch(context: Context, input: TvInput) {
        if (input.id.startsWith("demo/")) return
        val view = if (input.kind == InputKind.Tuner) {
            Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelsUriForInput(input.id), TvContract.Channels.CONTENT_TYPE)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(input.id), TvContract.Channels.CONTENT_ITEM_TYPE)
        }
        val started = runCatching { context.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!started) runCatching { context.startActivity(Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The household's own name for an input, from the iPhone app; a blank one gives it its own name back. */
    fun rename(id: String, name: String) {
        val names = prefs.inputNames.toMutableMap()
        if (name.isBlank()) names.remove(id) else names[id] = name.trim()
        prefs.inputNames = names
        reload()
    }

    /**
     * The input on screen while the TV's player is in front: TCL notes it as a device number, its HDMI
     * ports being 15–18, the numbers its ports' ids end in (…/HW15). Null where it can't be read.
     */
    fun current(): String? {
        val device = runCatching { Settings.Secure.getInt(context.contentResolver, TCL_INPUT_DEVICE, -1) }.getOrDefault(-1)
        if (device < 0) return null
        return list.firstOrNull { it.id.endsWith("/HW$device") }?.id
    }

    private fun reload() {
        list = if (demo) demoList() else runCatching { read() }.getOrElse {
            Log.w(TAG, "Couldn't read the TV inputs", it)
            emptyList()
        }
        Log.i(TAG, "Inputs: " + list.joinToString { "${it.id} '${it.name}' (${it.port}) ${it.kind}${if (it.connected) "" else ", nothing on it"}" })
    }

    private fun read(): List<TvInput> {
        val m = manager ?: return emptyList()
        val infos = m.tvInputList.filter { !it.isHidden(context) }
        // The TV's own sockets: its HDMI ports (passthrough inputs) and whatever else their service
        // offers (the aerial). Apps add tuner inputs of their own (Google TV, TCL Channel+), which
        // aren't sockets; and a device found on a port over HDMI-CEC is an input with the port as parent.
        val tv = infos.filter { it.isPassthroughInput }.map { it.serviceInfo.packageName }.toSet()
        val names = prefs.inputNames
        return infos.filter { it.parentId == null && it.serviceInfo.packageName in tv }.map { port ->
            val portName = port.loadLabel(context).toString()
            val device = infos.firstOrNull { it.parentId == port.id }?.let(::label)?.takeIf { it != portName }
            val name = names[port.id] ?: port.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() } ?: device ?: portName
            val kind = when (port.type) {
                TvInputInfo.TYPE_TUNER -> InputKind.Tuner
                TvInputInfo.TYPE_HDMI, TvInputInfo.TYPE_DVI, TvInputInfo.TYPE_DISPLAY_PORT -> InputKind.Hdmi
                TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART, TvInputInfo.TYPE_COMPONENT -> InputKind.Composite
                else -> InputKind.Other
            }
            val connected = kind == InputKind.Tuner || device != null || m.getInputState(port.id) != TvInputManager.INPUT_STATE_DISCONNECTED
            TvInput(port.id, name, portName.takeIf { it != name }, kind, connected)
        }.sortedWith(compareBy({ it.kind.ordinal }, { (it.port ?: it.name).filter(Char::isDigit).toIntOrNull() ?: 0 }))
    }

    private fun label(info: TvInputInfo) =
        (info.loadCustomLabel(context)?.takeIf { it.isNotBlank() } ?: info.loadLabel(context)).toString()

    /** The made-up inputs, under the household's names for them, as the real ones are. */
    private fun demoList() = DEMO.map { input ->
        prefs.inputNames[input.id]?.let { input.copy(name = it, port = input.port ?: input.name) } ?: input
    }

    companion object {
        /** The TV's own inputs panel. */
        const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"

        private const val TAG = "TvInputs"

        /** Where TCL notes the input in front (as QuickSettingsOverlay reads it too). */
        private const val TCL_INPUT_DEVICE = "tcl_sys_tv_input_current_device_id"

        private val DEMO = listOf(
            TvInput("demo/tv", "TV", null, InputKind.Tuner, true),
            TvInput("demo/hdmi1", "PlayStation 5", "HDMI 1", InputKind.Hdmi, true),
            TvInput("demo/hdmi2", "Apple TV", "HDMI 2", InputKind.Hdmi, true),
            TvInput("demo/hdmi3", "HDMI 3", null, InputKind.Hdmi, false),
            TvInput("demo/av", "AV", null, InputKind.Composite, false),
        )
    }
}
