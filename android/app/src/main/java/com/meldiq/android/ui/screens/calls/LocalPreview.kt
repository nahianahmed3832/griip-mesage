package com.meldiq.android.ui.screens.calls

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Where your own camera preview sits in a one-to-one call (UI-003); dragged, it settles in the nearest corner. */
enum class PreviewCorner {
    TopStart, TopEnd, BottomStart, BottomEnd;

    val top: Boolean get() = this == TopStart || this == TopEnd
    val start: Boolean get() = this == TopStart || this == BottomStart

    companion object {
        /** The corner nearest to a preview whose centre was dropped at ([x], [y]) on a stage of [width]×[height]. */
        fun nearest(x: Float, y: Float, width: Float, height: Float): PreviewCorner {
            val left = x < width / 2
            val up = y < height / 2
            return when {
                up && left -> TopStart
                up -> TopEnd
                left -> BottomStart
                else -> BottomEnd
            }
        }
    }
}

/**
 * Your own camera, movable out of the way of what the other person shows: drag it anywhere and it snaps to a corner,
 * clear of the status lines at the top and the controls at the bottom.
 */
@Composable
fun BoxScope.MovablePreview(
    corner: PreviewCorner, stage: IntSize, showChrome: Boolean, onCorner: (PreviewCorner) -> Unit,
    bottomExtra: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable BoxScope.() -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val align = when (corner) {
        PreviewCorner.TopStart -> Alignment.TopStart
        PreviewCorner.TopEnd -> Alignment.TopEnd
        PreviewCorner.BottomStart -> Alignment.BottomStart
        PreviewCorner.BottomEnd -> Alignment.BottomEnd
    }
    val insets = CallInsets.only(if (corner.top) WindowInsetsSides.Top + WindowInsetsSides.Horizontal else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    Box(
        Modifier.align(align).windowInsetsPadding(insets)
            .padding(top = if (corner.top) (if (showChrome) 92.dp else 16.dp) else 0.dp, bottom = if (corner.top) 0.dp else (if (showChrome) 150.dp + bottomExtra else 16.dp), start = 16.dp, end = 16.dp)
            .size(110.dp, 150.dp)
            .onGloballyPositioned { origin = it.positionInParent() }
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .semantics { contentDescription = "Your camera. Drag to move it." }
            .pointerInput(corner, stage) {
                detectDragGestures(
                    onDragEnd = {
                        val cx = origin.x + drag.x + size.width / 2f
                        val cy = origin.y + drag.y + size.height / 2f
                        drag = Offset.Zero
                        onCorner(PreviewCorner.nearest(cx, cy, stage.width.toFloat(), stage.height.toFloat()))
                    },
                    onDragCancel = { drag = Offset.Zero },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
        content = content,
    )
}
