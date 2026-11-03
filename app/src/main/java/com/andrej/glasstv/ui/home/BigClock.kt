package com.andrej.glasstv.ui.home

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrej.glasstv.ui.DisplayFont
import com.andrej.glasstv.ui.LocalScreenPx
import com.andrej.glasstv.ui.TextFont
import com.andrej.glasstv.ui.glass.Backdrop
import com.andrej.glasstv.ui.glass.LocalBackdrop
import com.andrej.glasstv.ui.textOutline

private val TimeStyle = TextStyle(
    fontFamily = DisplayFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 168.sp,
    letterSpacing = (-3.5).sp,
    fontFeatureSettings = "tnum",
)

private val DateStyle = TextStyle(
    fontFamily = TextFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    color = Color.White.copy(alpha = 0.92f),
)

/**
 * iOS 26 Lock Screen style clock: date above, huge Display numerals below. In the glass style the
 * numerals are cut from the frosted wallpaper with a specular rim, like the Liquid Glass clock.
 */
@Composable
fun BigClock(time: String, date: String, glass: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val layout = remember(time, measurer) { measurer.measure(time, TimeStyle) }
    val resolver = LocalFontFamilyResolver.current
    val typeface = remember(resolver) { resolver.resolve(TimeStyle.fontFamily, FontWeight.SemiBold).value as? Typeface }
    val density = LocalDensity.current
    // The numerals' merged outline (textOutline), on the layout's baseline: every pass draws it.
    val outline = remember(time, typeface, density) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = with(density) { TimeStyle.fontSize.toPx() }
            letterSpacing = TimeStyle.letterSpacing.value / TimeStyle.fontSize.value
            fontFeatureSettings = TimeStyle.fontFeatureSettings
        }
        textOutline(paint, time).first.apply { translate(Offset(0f, layout.firstBaseline)) }
    }
    val backdrop = LocalBackdrop.current
    val screen = LocalScreenPx.current
    val origin = remember { mutableStateOf(Offset.Zero) }
    val size = with(LocalDensity.current) { layout.size.width.toDp() to layout.size.height.toDp() }
    // Room around the numerals so the offscreen layer doesn't clip the soft shadow.
    val pad = 48.dp

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val bright = backdrop?.bright == true
        BasicText(date, style = DateStyle.copy(shadow = Shadow(Color.Black.copy(alpha = if (bright) 0.45f else 0.25f), Offset(0f, 1f), if (bright) 14f else 8f)))
        Canvas(
            Modifier
                .offset(y = (-12).dp - pad)
                .size(size.first + pad * 2, size.second + pad * 2)
                .onGloballyPositioned { origin.value = it.positionInRoot() }
                // Rendered once per minute into its own layer; focus animations never repaint it.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val p = pad.toPx()
            translate(p, p) { drawClock(outline, layout.firstBaseline, glass, backdrop, screen, origin.value + Offset(p, p)) }
        }
    }
}

private fun DrawScope.drawClock(outline: Path, baseline: Float, glass: Boolean, backdrop: Backdrop?, screen: Size, origin: Offset) {
    val top = baseline - TimeStyle.fontSize.toPx() * 0.73f
    val height = baseline - top

    // The numerals themselves are nearly invisible here; this pass only lays down the soft drop shadow.
    val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(5, 0, 0, 0)
        val strength = if (backdrop?.bright == true) 0.5f else 0.32f
        setShadowLayer(26.dp.toPx(), 0f, 6.dp.toPx(), android.graphics.Color.argb((strength * 255).toInt(), 0, 0, 0))
    }
    drawIntoCanvas { it.nativeCanvas.drawPath(outline.asAndroidPath(), shadow) }
    if (!glass || backdrop == null) {
        drawPath(outline, Color.White.copy(alpha = 0.95f))
        return
    }
    val shader = ImageShader(backdrop.clockFill)
    shader.setLocalMatrix(backdrop.coverMatrix(backdrop.clockFill, screen).apply { postTranslate(-origin.x, -origin.y) })
    drawPath(outline, ShaderBrush(shader))
    // gloss on the upper half of each numeral
    drawPath(outline, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.5f), 1f to Color.Transparent, startY = top, endY = top + height * 0.55f))
    // glass thickness: slightly darker towards the base
    drawPath(outline, Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f), startY = top + height * 0.5f, endY = baseline))
    // specular rim
    drawPath(
        outline,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.95f),
            0.5f to Color.White.copy(alpha = 0.3f),
            1f to Color.White.copy(alpha = 0.55f),
            startY = top,
            endY = baseline,
        ),
        style = Stroke(width = 1.4.dp.toPx(), join = StrokeJoin.Round),
    )
}
