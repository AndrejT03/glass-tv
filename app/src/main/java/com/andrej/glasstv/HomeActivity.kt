package com.andrej.glasstv

import android.Manifest
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.andrej.glasstv.controlcenter.ControlCenterModel
import com.andrej.glasstv.controlcenter.Controls
import com.andrej.glasstv.controlcenter.Tile
import com.andrej.glasstv.data.AppEntry
import com.andrej.glasstv.data.TvInput
import com.andrej.glasstv.data.TvInputs
import com.andrej.glasstv.screensaver.ScreensaverActivity
import com.andrej.glasstv.ui.DesignScale
import com.andrej.glasstv.ui.Sounds
import com.andrej.glasstv.ui.home.HomeActions
import com.andrej.glasstv.ui.home.HomeController
import com.andrej.glasstv.ui.home.HomeRoot
import com.andrej.glasstv.ui.home.Panel
import com.andrej.glasstv.ui.stringsFor
import com.andrej.glasstv.ui.unscaledRecomposer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class HomeActivity : ComponentActivity(), HomeActions {
    private val glass get() = GlassApp.instance
    private lateinit var controller: HomeController
    private lateinit var sounds: Sounds
    private val handler = Handler(Looper.getMainLooper())

    // Allowed with the control center still open: connect to the light's strip now.
    private val bluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && controller.panel == Panel.ControlCenter) glass.ambient.open()
    }

    // Only from the home screen itself. The release of the Back that starts the screen saver can
    // reach this window after it has been paused, and its timer then started the saver again over
    // itself: that paused the saver, and a paused saver closes, so it went back to the home screen
    // "Start after" minutes after it was started with the remote.
    private val idle = Runnable { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startScreensaver() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lifecycleScope.launch { glass.apps.reload() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            glass.apps.demo = intent.getBooleanExtra("demo", false)
            glass.inputs.demo = intent.getBooleanExtra("demo", false)
            glass.recents.demo = intent.getBooleanExtra("demo", false)
            glass.stats.demo = intent.getBooleanExtra("demo", false)
            glass.picture.demo = intent.getBooleanExtra("demo", false)
        }
        sounds = Sounds(this, glass.prefs)
        val hasInputs = glass.inputs.list.isNotEmpty() || packageManager.queryIntentActivities(Intent(TvInputs.ACTION_VIEW_INPUTS), 0).isNotEmpty()
        controller = HomeController(
            glass.apps,
            glass.prefs,
            this,
            hasInputs,
            { glass.inputs.list },
            { glass.recents.list },
            ControlCenterModel(glass.volume, glass.ambient),
        )
        savedInstanceState?.let(controller::restoreState)
        // A launcher never finishes on Back.
        onBackPressedDispatcher.addCallback(this) { controller.goHome() }
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent(parent = unscaledRecomposer(this, lifecycle)) {
            DesignScale { HomeRoot(controller) }
        }
        // The wallpaper is opaque; the window background would only be overdraw.
        window.setBackgroundDrawable(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        controller.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) controller.goHome()
    }

    override fun onRestart() {
        super.onRestart()
        controller.returning = true
    }

    override fun onResume() {
        super.onResume()
        front = this
        glass.recents.front = null
        scheduleIdle()
    }

    override fun onPause() {
        super.onPause()
        if (front === this) front = null
        handler.removeCallbacks(idle)
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        sounds.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) scheduleIdle()
        return super.dispatchKeyEvent(event)
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idle)
        val minutes = glass.prefs.idleMinutes
        if (minutes > 0) handler.postDelayed(idle, minutes * 60_000L)
    }

    // region HomeActions

    override fun launch(app: AppEntry, tile: Rect) {
        glass.recents.opened(app.packageName)
        // The app zooms out of its icon, like tvOS.
        val u = resources.displayMetrics.widthPixels / 960f
        val options = ActivityOptions.makeScaleUpAnimation(
            window.decorView,
            (tile.left * u).roundToInt(),
            (tile.top * u).roundToInt(),
            (tile.width * u).roundToInt(),
            (tile.height * u).roundToInt(),
        )
        val started = runCatching { startActivity(app.launchIntent(), options.toBundle()) }.isSuccess
        if (!started) {
            (packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName))
                ?.let { runCatching { startActivity(it) } }
        }
    }

    override fun appInfo(app: AppEntry) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
        }
    }

    override fun uninstall(app: AppEntry) {
        runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))) }
    }

    override fun openInputs() {
        runCatching { startActivity(Intent(TvInputs.ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun switchInput(input: TvInput) = glass.inputs.watch(this, input)

    override fun openAndroidSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    override fun startScreensaver(preview: Boolean) {
        handler.removeCallbacks(idle)
        startActivity(ScreensaverActivity.intent(this, preview))
    }

    override fun control(tile: Tile): Boolean {
        if (tile != Tile.Screensaver) return Controls.run(this, tile)
        startScreensaver()
        return true
    }

    override fun powerOffNotice() = stringsFor(glass.prefs.language).needsService

    override fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    override fun tick() = sounds.tick()

    override fun select() = sounds.select()

    // endregion

    /** On the home screen, the remote's Input button opens the home screen's own inputs menu, or closes it. */
    fun toggleInputsMenu() = controller.toggleInputsMenu()

    /** On the home screen, the remote's Home button held opens the home screen's own control center, or closes it. */
    fun toggleControlCenter() = controller.toggleControlCenter()

    /** Closes the home screen's own menus, for the quick settings coming up over them. */
    fun closePanel() = controller.closePanel()

    /** The quick settings come up over the home screen (the home screen frosts under them), or go. */
    fun quickSettings(shown: Boolean) {
        controller.quickSettingsShown(shown)
        // Their keys never reach the home screen, so its screen saver would start under them: it waits
        // while they are up, and from when they go.
        if (shown) handler.removeCallbacks(idle) else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) scheduleIdle()
    }

    companion object {
        /** The home screen while it is in front, for the Input button (InputsKeyService). */
        var front: HomeActivity? = null
            private set

    }
}
