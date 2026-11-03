package com.andrej.glasstv.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.R
import java.io.File

/**
 * The launcher's typefaces: Apple's own, SF Pro and SF Pro Rounded, when they are on the TV in
 * Android/data/com.andrej.glasstv/files/fonts/ (SFNS.ttf and SFNSRounded.ttf; tools/push_fonts.sh
 * copies them from a Mac). Apple licenses them for its own platforms, so the app doesn't ship them;
 * without them Inter, Inter Display and Nunito, the closest freely licensed matches, stand in.
 */
object AppFonts {
    private val Inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )

    private val InterDisplay = FontFamily(
        Font(R.font.inter_display_medium, FontWeight.Medium),
        Font(R.font.inter_display_semibold, FontWeight.SemiBold),
        Font(R.font.inter_display_bold, FontWeight.Bold),
    )

    var text: FontFamily = Inter
        private set
    var display: FontFamily = InterDisplay
        private set
    private var rounded: File? = null

    /** Picks up SF Pro if it is on the TV. Called once, before any text is drawn. */
    fun load(context: Context) {
        val dir = context.getExternalFilesDir("fonts") ?: return
        File(dir, "SFNS.ttf").takeIf { it.isFile && it.length() > 0 }?.let { sf ->
            // tvOS sizes text in points of a 1920x1080 screen, two per design unit here, so even the
            // smallest labels (~25 pt) use SF Pro's display optical size, as on Apple TV.
            text = sfPro(sf, opticalSize = 32f)
            display = sfPro(sf, opticalSize = 80f)
        }
        rounded = File(dir, "SFNSRounded.ttf").takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * The tvOS 26 screen saver clock face, SF Pro Rounded. A font at fonts/clock.ttf (or .otf)
     * overrides it; without either, Nunito (with its plain "1", ss01) stands in.
     */
    @OptIn(ExperimentalTextApi::class)
    fun clock(context: Context): FontFamily {
        val custom = context.getExternalFilesDir("fonts")?.listFiles()
            ?.firstOrNull { it.nameWithoutExtension == "clock" && it.extension in setOf("ttf", "otf") }
        val file = custom ?: rounded
        if (file != null) {
            return FontFamily(Font(file, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(if (custom != null) 530 else SF_MEDIUM))))
        }
        return FontFamily(Font(R.font.nunito, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(580))))
    }

    /**
     * Shapes [sample] in every weight of the text and display faces, on the calling thread (not the
     * main one). The typefaces exist from [load] on, but the first text set in a weight read SF Pro's
     * tables from disk (8 MB, a variable font) and worked out that weight's instance: the control
     * center's first opening on the TV spent most of its 87 ms of text measuring on that, ~30 ms of
     * it waiting for the disk.
     */
    fun warmUp(context: Context, sample: String) {
        val resolver = createFontFamilyResolver(context)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f }
        for (family in listOf(text, display)) {
            for (weight in listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)) {
                paint.typeface = resolver.resolve(family, weight).value as? Typeface ?: continue
                paint.measureText(sample)
            }
        }
    }

    /** SF Pro's weights are its named instances (Medium is 510, Semibold 590), not CSS's round numbers. */
    @OptIn(ExperimentalTextApi::class)
    private fun sfPro(file: File, opticalSize: Float) = FontFamily(
        listOf(FontWeight.Normal to 400, FontWeight.Medium to SF_MEDIUM, FontWeight.SemiBold to 590, FontWeight.Bold to 700).map { (weight, wght) ->
            Font(file, weight, variationSettings = FontVariation.Settings(FontVariation.weight(wght), FontVariation.Setting("opsz", opticalSize)))
        },
    )

    private const val SF_MEDIUM = 510
}

/** UI text: SF Pro when it is on the TV, else Inter. */
val TextFont: FontFamily get() = AppFonts.text

/** Big text and numerals: SF Pro at display optical size, else Inter Display. */
val DisplayFont: FontFamily get() = AppFonts.display

object Palette {
    val Label = Color(0xFFFFFFFF)
    val Secondary = Color(0xB3FFFFFF)
    val OnFocus = Color(0xFF1C1C1E)
    val Destructive = Color(0xFFFF453A)
}

object Type {
    val Body = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Palette.Label)
    val Caption = TextStyle(fontFamily = TextFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Palette.Label)
    val Title = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.4).sp, color = Palette.Label)
}

/** Screen size in real pixels, used to map glass sampling onto the wallpaper. */
val LocalScreenPx = staticCompositionLocalOf { Size(1920f, 1080f) }

/**
 * Lays everything out on a fixed 960x540 canvas (1 dp = 1/960 of the screen width), so the
 * tvOS proportions are identical on any TV resolution or density. Every window of Glass starts
 * here, so this is also where its semantics are left out when no screen reader needs them.
 */
@Composable
fun DesignScale(content: @Composable () -> Unit) {
    val config: Configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val widthPx = config.screenWidthDp * density
    val heightPx = config.screenHeightDp * density
    CompositionLocalProvider(
        LocalDensity provides Density(widthPx / 960f, 1f),
        LocalScreenPx provides Size(widthPx, heightPx),
    ) {
        // Passes the window's constraints on as they are, so the content lays out as it did at the root.
        Box(Modifier.fillMaxSize().semanticsForScreenReaders(), propagateMinConstraints = true) { content() }
    }
}
