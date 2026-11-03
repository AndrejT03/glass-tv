package com.andrej.glasstv

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.andrej.glasstv.controlcenter.AmbientLight
import com.andrej.glasstv.controlcenter.SystemStats
import com.andrej.glasstv.controlcenter.Volume
import com.andrej.glasstv.data.AppsRepository
import com.andrej.glasstv.data.Prefs
import com.andrej.glasstv.data.RecentApps
import com.andrej.glasstv.data.TvInputs
import com.andrej.glasstv.quicksettings.SleepTimer
import com.andrej.glasstv.quicksettings.TvPicture
import com.andrej.glasstv.remote.RemoteServer
import com.andrej.glasstv.screensaver.AerialCache
import com.andrej.glasstv.screensaver.DynamicWallpapers
import com.andrej.glasstv.ui.AppFonts
import com.andrej.glasstv.ui.EN
import com.andrej.glasstv.ui.MK
import com.andrej.glasstv.ui.home.Grid
import com.andrej.glasstv.ui.glass.TopShelfStore
import com.andrej.glasstv.ui.glass.WallpaperStore

class GlassApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppsRepository
        private set
    lateinit var wallpapers: WallpaperStore
        private set
    lateinit var topShelf: TopShelfStore
        private set
    lateinit var inputs: TvInputs
        private set
    lateinit var recents: RecentApps
        private set

    /** Apple's dynamic desktop pictures, where push_dynamic.sh has put them on the TV. */
    lateinit var dynamic: DynamicWallpapers
        private set

    // The control center's, shared by the one on the home screen and the one over other apps.
    lateinit var stats: SystemStats
        private set
    lateinit var volume: Volume
        private set
    lateinit var ambient: AmbientLight
        private set

    // The quick settings' (the TCL remote's button right of 0).
    lateinit var picture: TvPicture
        private set
    val sleepTimer = SleepTimer()

    /** The TV is awake (its screen on): Compose state, so the iPhone app hears it go to sleep and wake. */
    var screenOn by mutableStateOf(true)
        private set

    /** The iPhone app's way in, on the home network. */
    lateinit var remote: RemoteServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppFonts.load(this)
        prefs = Prefs(this)
        picture = TvPicture(prefs)
        apps = AppsRepository(this, prefs, Grid.artWidth(resources.displayMetrics.widthPixels))
        wallpapers = WallpaperStore(this)
        topShelf = TopShelfStore(this)
        inputs = TvInputs(this, prefs).also { it.start() }
        recents = RecentApps(this, prefs, apps) { setOfNotNull(inputs.playerPackage) }
        dynamic = DynamicWallpapers(this).also { it.refresh() }
        stats = SystemStats(this)
        volume = Volume(this)
        ambient = AmbientLight(this, prefs)
        // Ambient Sync turns the strip on with the screen, so the light tile starts from on again; and
        // off a while after it, so Glass lets the strip go when the screen goes off. A sleep timer
        // ends with the screen too.
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
                if (screenOn) {
                    ambient.screenOn()
                } else {
                    ambient.screenOff()
                    sleepTimer.screenOff()
                }
            }
        }
        val screenActions = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(this, screen, screenActions, ContextCompat.RECEIVER_NOT_EXPORTED)
        remote = RemoteServer(this).also { it.start() }
        // Opening the aerials' disk cache reads its index, slow on the TV: start it now, so the screen
        // saver and the live wallpaper never wait for it.
        Thread({ AerialCache.get(this) }, "aerial-cache").start()
        // The same for the fonts: every string the launcher shows, shaped once in each weight, so no
        // screen waits for SF Pro to be read the first time it comes up.
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            AppFonts.warmUp(this, "$MK $EN 0123456789:%")
        }, "fonts").start()
    }

    companion object {
        lateinit var instance: GlassApp
            private set
    }
}
