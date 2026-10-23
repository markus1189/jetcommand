package link.thesymbolic.jetcommand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val DeleteFg = Color(0xFFF0A070)
private val DeleteBg = Color(0xFF2A1D14)
private val PanelWidth = 88.dp

private enum class Reveal { Closed, Open }

/**
 * Swiping left reveals a delete panel and stops: there is no anchor past it, so even a hard fling only opens it.
 * [isOpen]/[setOpen] are hoisted so the list can keep a single row open.
 */
@Composable
fun SwipeToDelete(
    label: String,
    isOpen: Boolean,
    setOpen: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val panelPx = with(LocalDensity.current) { PanelWidth.toPx() }
    val state = remember(panelPx) {
        AnchoredDraggableState(Reveal.Closed, DraggableAnchors { Reveal.Closed at 0f; Reveal.Open at -panelPx })
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { snapshotFlow { state.settledValue }.collect { setOpen(it == Reveal.Open) } }
    LaunchedEffect(isOpen) { if (!isOpen && state.settledValue == Reveal.Open) state.animateTo(Reveal.Closed) }
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(DeleteBg)) {
        Column(
            Modifier.matchParentSize().wrapContentWidth(Alignment.End).width(PanelWidth)
                .clickable(onClickLabel = "Delete") { onDelete() }.semantics { contentDescription = "Delete $label" },
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TrashIcon(22.dp)
            Text("Delete", fontSize = 12.sp, color = DeleteFg)
        }
        content(
            Modifier.offset { IntOffset(state.offset.takeIf { !it.isNaN() }?.roundToInt() ?: 0, 0) }
                .anchoredDraggable(state, Orientation.Horizontal)
                .clickable {
                    if (state.settledValue == Reveal.Open) scope.launch { state.animateTo(Reveal.Closed) } else onClick()
                },
        )
    }
}

/** The design's trash glyph (24-unit viewBox), drawn so the app needs no icon dependency. */
@Composable
fun TrashIcon(size: Dp, color: Color = DeleteFg) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    val p = Path().apply {
        moveTo(4 * u, 7 * u); lineTo(20 * u, 7 * u)
        moveTo(10 * u, 11 * u); lineTo(10 * u, 17 * u)
        moveTo(14 * u, 11 * u); lineTo(14 * u, 17 * u)
        moveTo(6 * u, 7 * u); lineTo(7 * u, 20 * u); lineTo(17 * u, 20 * u); lineTo(18 * u, 7 * u)
        moveTo(9 * u, 7 * u); lineTo(9 * u, 4 * u); lineTo(15 * u, 4 * u); lineTo(15 * u, 7 * u)
    }
    drawPath(p, color, style = Stroke(2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
