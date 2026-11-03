package com.andrej.glasstv.ui

import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil

/**
 * Keeps what moves inside this element from redrawing the whole screen.
 *
 * Android redraws only the part of a window that changed, but HWUI can't tell how far a layer that
 * doesn't clip to its bounds reaches, nor one turned in perspective (a tilting icon), and a Compose
 * layer never clips to its bounds: so any change in one counts the whole window as changed, and the
 * TV's GPU redrew the wallpaper, the dock and every icon on every frame of every animation. Drawn
 * through a RenderNode of its own that clips to [left], [top], [right] and [bottom] around this
 * element, a layer in here that only moves, scales, turns or fades marks just that much of the screen.
 *
 * Everything drawn in here must stay inside those margins, or it is cut off. A change that makes
 * Compose draw a layer's content again still redraws everything (it redraws the whole window's view).
 */
fun Modifier.damageBounds(left: Dp, top: Dp, right: Dp, bottom: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= 29) this then DamageBoundsElement(left, top, right, bottom) else this

private data class DamageBoundsElement(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp) :
    ModifierNodeElement<DamageBoundsNode>() {
    @RequiresApi(29)
    override fun create() = DamageBoundsNode(left, top, right, bottom)

    @RequiresApi(29)
    override fun update(node: DamageBoundsNode) {
        node.left = left
        node.top = top
        node.right = right
        node.bottom = bottom
        node.invalidateDraw()
    }
}

@RequiresApi(29)
private class DamageBoundsNode(var left: Dp, var top: Dp, var right: Dp, var bottom: Dp) : Modifier.Node(), DrawModifierNode {
    // A new RenderNode clips to its bounds, and with no elevation of its own HWUI clips damage to them too.
    private var renderNode: RenderNode? = null

    override fun onDetach() {
        renderNode?.discardDisplayList()
        renderNode = null
    }

    override fun ContentDrawScope.draw() {
        val target = drawContext.canvas
        val native = target.nativeCanvas
        // A picture of the screen (the frost) is drawn in software, where RenderNodes can't go.
        if (!native.isHardwareAccelerated) {
            drawContent()
            return
        }
        val l = left.roundToPx()
        val t = top.roundToPx()
        val w = ceil(size.width).toInt()
        val h = ceil(size.height).toInt()
        val bounds = renderNode ?: RenderNode("damageBounds").also { renderNode = it }
        bounds.setPosition(-l, -t, w + right.roundToPx(), h + bottom.roundToPx())
        val recording = bounds.beginRecording()
        try {
            recording.translate(l.toFloat(), t.toFloat())
            // The content draws into the node as it would have drawn here.
            drawContext.canvas = Canvas(recording)
            drawContent()
        } finally {
            drawContext.canvas = target
            bounds.endRecording()
        }
        native.drawRenderNode(bounds)
    }
}
