package com.andrej.glasstv.ui

import android.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath

/**
 * The outline of [text] with its baseline at 0, overlapping contours merged into one shape, and its
 * advance. SF Pro and SF Pro Rounded build some digits from overlapping contours (the 2's tail runs
 * into its base); a rim stroked straight from the font traces those overlaps as a knot inside the
 * digit, so the glass numerals are drawn from this instead.
 */
fun textOutline(paint: Paint, text: String): Pair<Path, Float> {
    val raw = android.graphics.Path()
    paint.getTextPath(text, 0, text.length, 0f, 0f, raw)
    val merged = android.graphics.Path().apply { op(raw, android.graphics.Path.Op.UNION) }
    return merged.asComposePath() to paint.measureText(text)
}
