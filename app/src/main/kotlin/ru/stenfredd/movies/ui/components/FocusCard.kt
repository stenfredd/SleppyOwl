package ru.stenfredd.movies.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.stenfredd.movies.ui.theme.AppColors

/** Focus look of a card: scale, outer ring and glow, as `style-focus` in the mockup (sizes in dp). */
@Immutable
data class FocusStyle(
    val scale: Float,
    val corner: Dp,
    val ring: Dp = 1.5.dp,
    val ringColor: Color = AppColors.Accent,
    val glows: List<Glow> = CardGlows,
    /**
     * False: focus shows at once. Over video (the player) every frame redraws the controls' shades too, so an
     * animated focus change costs ~8 expensive frames instead of one.
     */
    val animate: Boolean = true,
) {
    @Immutable
    data class Glow(val blur: Dp, val spread: Dp, val color: Color)

    companion object {
        /** `0 0 32px 6px rgba(124,92,255,.55), 0 0 90px 14px rgba(70,120,255,.22)` */
        val CardGlows = listOf(
            Glow(16.dp, 3.dp, Color(0x8C7C5CFF)),
            Glow(45.dp, 7.dp, Color(0x384678FF)),
        )

        /** `0 0 30px 6px rgba(124,92,255,.5)` — buttons and chips. */
        val ButtonGlows = listOf(Glow(15.dp, 3.dp, Color(0x807C5CFF)))

        val Poster = FocusStyle(scale = 1.07f, corner = 7.dp)
        val Wide = FocusStyle(scale = 1.05f, corner = 8.dp)
        val Featured = FocusStyle(scale = 1.025f, corner = 11.dp)
        val Tile = FocusStyle(scale = 1.03f, corner = 11.dp, ring = 1.dp)
    }
}

/**
 * Focus decoration for a card: animates scale, ring and glow when the element (or a child) gets focus.
 * Put it before `clip` and `clickable`/`focusable` so the glow isn't clipped and focus events reach it.
 *
 * Implemented as a modifier node: the animation only touches the layer and draw phases,
 * a focus change never recomposes the card.
 */
fun Modifier.focusCard(style: FocusStyle): Modifier = this then FocusCardElement(style)

private data class FocusCardElement(val style: FocusStyle) : ModifierNodeElement<FocusCardNode>() {
    override fun create() = FocusCardNode(style)
    override fun update(node: FocusCardNode) {
        node.style = style
        node.invalidateDraw()
    }
}

private class FocusCardNode(var style: FocusStyle) : Modifier.Node(), FocusEventModifierNode, LayoutModifierNode, DrawModifierNode {
    private val progress = Animatable(0f)
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus == focused) return
        focused = focusState.hasFocus
        if (!isAttached) return
        invalidatePlacement()
        val target = if (focused) 1f else 0f
        coroutineScope.launch { if (style.animate) progress.animateTo(target, tween(if (focused) 180 else 140)) else progress.snapTo(target) }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // Focused card on top so its scaled edge and glow aren't covered by neighbours.
            placeable.placeWithLayer(IntOffset.Zero, zIndex = if (focused) 1f else 0f) {
                val s = 1f + (style.scale - 1f) * progress.value
                scaleX = s
                scaleY = s
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = progress.value
        if (p > 0f) {
            val corner = style.corner.toPx()
            if (style.glows.isNotEmpty()) drawGlow(corner, p)
            val ring = style.ring.toPx()
            drawRoundRect(
                color = style.ringColor,
                topLeft = Offset(-ring / 2, -ring / 2),
                size = Size(size.width + ring, size.height + ring),
                cornerRadius = CornerRadius(corner + ring / 2),
                style = Stroke(ring),
                alpha = p,
            )
        }
        drawContent()
    }

    private fun ContentDrawScope.drawGlow(corner: Float, p: Float) {
        val glow = GlowCache.get(
            size.width,
            size.height,
            corner,
            style.glows.map { GlowLayer(it.blur.toPx(), it.spread.toPx(), it.color) },
        )
        drawImage(
            image = glow.bitmap,
            dstOffset = IntOffset(-glow.marginPx.toInt(), -glow.marginPx.toInt()),
            dstSize = IntSize(
                (size.width + 2 * glow.marginPx).toInt(),
                (size.height + 2 * glow.marginPx).toInt(),
            ),
            alpha = p,
        )
    }
}
