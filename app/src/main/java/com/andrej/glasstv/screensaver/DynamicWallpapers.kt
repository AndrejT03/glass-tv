package com.andrej.glasstv.screensaver

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where the sun is: degrees above the horizon, and the compass bearing (0 north, 90 east). */
data class SunPosition(val altitude: Double, val azimuth: Double)

/** One of Apple's dynamic desktop pictures: its frames, and the sun's positions each one is for. */
class DynamicWallpaper(
    val id: String,
    val name: String,
    val frames: Int,
    /** The sun's positions macOS chooses between, each with its frame (several may share one). */
    private val sun: List<Pair<SunPosition, Int>>,
) {
    /** The frame for the sun at [at]: the one whose position is nearest it in the sky, as on the Mac. */
    fun frameFor(at: SunPosition): Int {
        val a1 = Math.toRadians(at.altitude)
        var best = 0
        var nearest = -2.0
        for ((p, frame) in sun) {
            val a2 = Math.toRadians(p.altitude)
            // The cosine of the angle between them: the bigger, the nearer.
            val closeness = sin(a1) * sin(a2) + cos(a1) * cos(a2) * cos(Math.toRadians(at.azimuth - p.azimuth))
            if (closeness > nearest) {
                nearest = closeness
                best = frame
            }
        }
        return best.coerceIn(0, frames - 1)
    }
}

/** The part of the day the sun says it is, as the caption names it. */
enum class DayPart { Night, Dawn, Sunrise, Morning, Midday, Afternoon, Sunset, Dusk;

    companion object {
        fun of(sun: SunPosition): DayPart {
            val morning = sun.azimuth < 180
            return when {
                sun.altitude < -12 -> Night
                sun.altitude < -4 -> if (morning) Dawn else Dusk
                sun.altitude < 6 -> if (morning) Sunrise else Sunset
                sun.azimuth in 155.0..205.0 -> Midday
                morning -> Morning
                else -> Afternoon
            }
        }
    }
}

/**
 * Apple's dynamic desktop pictures on the TV, which `tools/push_dynamic.sh` puts in
 * `Android/data/com.andrej.glasstv/files/dynamic/` (each picture's frames as `<id>/tv/<n>.jpg`, the
 * iPhone widgets' squares as `<id>/phone/<n>.jpg`, the sun's positions in `dynamic.json`). They are
 * Apple's pictures, so the app doesn't include them, as with Apple's fonts.
 */
class DynamicWallpapers(context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "dynamic")

    /** What is on the TV; null until first read. Compose state, for the settings to list. */
    var list by mutableStateOf<List<DynamicWallpaper>?>(null)
        private set

    /**
     * Reads `dynamic.json` (again, as push_dynamic.sh may have replaced it), off the main thread;
     * none if it isn't there.
     */
    fun read(): List<DynamicWallpaper> {
        val read = runCatching { parse(File(dir, "dynamic.json").readText()) }.getOrDefault(emptyList())
            .filter { File(dir, "${it.id}/tv/0.jpg").exists() }
        list = read
        return read
    }

    suspend fun load(): List<DynamicWallpaper> = withContext(Dispatchers.IO) { read() }

    /** [read], on a thread of its own: push_dynamic.sh may have put them there since Glass started. */
    fun refresh() {
        Thread({ read() }, "dynamic").start()
    }

    fun find(id: String): DynamicWallpaper? = list?.firstOrNull { it.id == id }

    fun tvFrame(wallpaper: DynamicWallpaper, frame: Int) = File(dir, "${wallpaper.id}/tv/$frame.jpg")

    fun phoneFrame(id: String, frame: Int): File? =
        File(dir, "$id/phone/$frame.jpg").takeIf { find(id) != null && frame >= 0 && it.isFile }

    /** The manifest as the phone gets it: the pictures, their frames and the sun's positions. */
    fun manifest(): String? = runCatching { File(dir, "dynamic.json").readText() }.getOrNull()

    private fun parse(text: String): List<DynamicWallpaper> {
        val wallpapers = JSONObject(text).getJSONArray("wallpapers")
        return (0 until wallpapers.length()).map { i ->
            val w = wallpapers.getJSONObject(i)
            val sun = w.getJSONArray("sun")
            DynamicWallpaper(
                id = w.getString("id"),
                name = w.getString("name"),
                frames = w.getInt("frames"),
                sun = (0 until sun.length()).map { k ->
                    val s = sun.getJSONObject(k)
                    SunPosition(s.getDouble("a"), s.getDouble("z")) to s.getInt("i")
                },
            )
        }
    }
}

/** The household's place, for where the sun is: Skopje (the TV has no location of its own). */
const val SUN_LATITUDE = 41.9981
const val SUN_LONGITUDE = 21.4254

/**
 * Where the sun is at [timeMs] seen from [latitude], [longitude]: NOAA's solar position equations,
 * to well under a degree, which is all choosing a frame needs. The iPhone widgets work it out the same
 * way (ios/Shared/Sun.swift), so they show the frame the TV does.
 */
fun sunAt(timeMs: Long, latitude: Double = SUN_LATITUDE, longitude: Double = SUN_LONGITUDE): SunPosition {
    val julianDay = timeMs / 86_400_000.0 + 2_440_587.5
    val t = (julianDay - 2_451_545.0) / 36_525.0
    val meanLong = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
    val anomaly = Math.toRadians(357.52911 + t * (35_999.05029 - 0.0001537 * t))
    val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    val center = sin(anomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
        sin(2 * anomaly) * (0.019993 - 0.000101 * t) + sin(3 * anomaly) * 0.000289
    val omega = Math.toRadians(125.04 - 1934.136 * t)
    val lambda = Math.toRadians(meanLong + center - 0.00569 - 0.00478 * sin(omega))
    val obliquity = Math.toRadians(
        23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 + 0.00256 * cos(omega),
    )
    val declination = asin(sin(obliquity) * sin(lambda))
    val y = tan(obliquity / 2).let { it * it }
    val l0 = Math.toRadians(meanLong)
    // The equation of time, in minutes.
    val eqTime = 4 * Math.toDegrees(
        y * sin(2 * l0) - 2 * eccentricity * sin(anomaly) + 4 * eccentricity * y * sin(anomaly) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * eccentricity * eccentricity * sin(2 * anomaly),
    )
    val utcMinutes = timeMs.mod(86_400_000L) / 60_000.0
    val solarMinutes = (utcMinutes + eqTime + 4 * longitude).mod(1440.0)
    val hourAngle = Math.toRadians(solarMinutes / 4 - 180)
    val lat = Math.toRadians(latitude)
    val zenith = acos((sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0))
    val azimuth = Math.toDegrees(atan2(sin(hourAngle), cos(hourAngle) * sin(lat) - tan(declination) * cos(lat))) + 180
    return SunPosition(90 - Math.toDegrees(zenith), azimuth.mod(360.0))
}
