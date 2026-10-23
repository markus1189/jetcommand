package link.thesymbolic.jetcommand

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface Press {
    data object Released : Press
    data object Dragging : Press
}

/**
 * A value in `0 until count`: tap the left/right half for one step, hold to repeat faster and faster, or drag
 * vertically for one step per [pxPerStep]. [onPreview] follows the finger; [onCommit] fires once, on release.
 * [hollow] draws the number outlined, for a value sent but not yet confirmed by the unit.
 */
@Composable
fun ValueField(
    label: String,
    text: String,
    sub: String,
    index: Int,
    count: Int,
    color: Color,
    enabled: Boolean,
    hollow: Boolean,
    onPreview: (Int?) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pxPerStep: Float = 40f,
) {
    val view = LocalView.current
    val slop = LocalViewConfiguration.current.touchSlop
    val current by rememberUpdatedState(index)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    fun tick() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
    )

    val gestures = if (!enabled) Modifier else Modifier.pointerInput(count) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val dir = if (down.position.x < size.width / 2) -1 else 1
            val start = current
            var value = start
            fun set(v: Int) {
                val c = v.coerceIn(0, count - 1)
                if (c == value) return
                value = c
                tick()
                preview(c)
            }

            val first = withTimeoutOrNull(450) {
                while (true) {
                    val change = awaitPointerEvent().changes.first()
                    if (!change.pressed) return@withTimeoutOrNull Press.Released
                    if (abs(change.position.y - down.position.y) > slop) return@withTimeoutOrNull Press.Dragging
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            when (first) {
                Press.Released -> set(start + dir)
                Press.Dragging -> while (true) {
                    val change = awaitPointerEvent().changes.first()
                    if (!change.pressed) break
                    change.consume()
                    set(start + ((down.position.y - change.position.y) / pxPerStep).roundToInt())
                }
                null -> {
                    var delay = 350L
                    do {
                        set(value + dir)
                        val released = withTimeoutOrNull(delay) {
                            while (awaitPointerEvent().changes.first().pressed) Unit
                        } != null
                        delay = maxOf(50L, delay * 3 / 4)
                    } while (!released)
                }
            }
            preview(null)
            if (value != start) commit(value)
        }
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.heightIn(min = 200.dp).clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).then(gestures),
    ) {
        val fraction = if (count > 1) index / (count - 1f) else 1f
        if (enabled) Box(
            Modifier.fillMaxWidth().fillMaxHeight(fraction.coerceIn(0f, 1f)).align(Alignment.BottomCenter)
                .background(color.copy(alpha = 0.22f)),
        )
        Column(
            Modifier.fillMaxSize().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = muted, fontSize = 14.sp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text,
                    style = TextStyle(
                        fontSize = 56.sp,
                        lineHeight = 60.sp,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else muted,
                        drawStyle = if (hollow) Stroke(width = 3f) else null,
                    ),
                )
                Text(sub, color = muted, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                if (enabled) {
                    Text("−", color = muted, fontSize = 28.sp)
                    Text("+", color = muted, fontSize = 28.sp)
                }
            }
        }
    }
}
