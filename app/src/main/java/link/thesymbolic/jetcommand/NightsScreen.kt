package link.thesymbolic.jetcommand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import link.thesymbolic.jetcommand.night.NightText
import link.thesymbolic.jetcommand.night.Watch
import link.thesymbolic.jetcommand.night.chips
import link.thesymbolic.jetcommand.night.stretches
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.NightsSource

val ChipWarnBorder = Color(0xFF5A4520)

/** The Nights tab: the list, and a night's review in place of it once one is picked. */
@Composable
fun NightsScreen(source: NightsSource) {
    val text = remember { NightText() }
    val nights by remember(source) { source.nights() }.collectAsState(initial = null)
    var open by remember { mutableStateOf<NightSummary?>(null) }
    val current = open
    if (current != null) {
        BackHandler { open = null }
        // The list re-emits as the ongoing night grows; follow it by start time.
        val latest = nights?.firstOrNull { it.start == current.start } ?: current
        NightReviewScreen(source, latest, text, onBack = { open = null })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text("Nights", style = MaterialTheme.typography.headlineMedium)
                Text("How the link held up, and what you changed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val list = nights
        when {
            list == null -> {}
            list.isEmpty() -> item {
                Text("No nights yet. A night is logged while the app is connected to a BedJet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> items(list, key = { it.start }) { NightCard(it, text) { open = it } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NightCard(s: NightSummary, text: NightText, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (s.ongoing) Modifier.border(1.dp, MaterialTheme.colorScheme.primaryContainer, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(if (s.ongoing) "Tonight" else text.title(s), style = MaterialTheme.typography.titleMedium)
            if (s.ongoing) Text("● recording since ${text.time(s.start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            else Text(text.span(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NightStrip(s, Modifier.fillMaxWidth().height(10.dp))
        if (s.ongoing && s.drops == 0 && s.killedAt.isEmpty()) {
            Text("Connected · no drops yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips(s, text).forEach { (label, warn) -> Chip(label, warn) }
        }
    }
}

@Composable
fun Chip(label: String, warn: Boolean) = Text(
    label,
    style = MaterialTheme.typography.labelMedium,
    color = if (warn) Amber else MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.border(1.dp, if (warn) ChipWarnBorder else MaterialTheme.colorScheme.outline, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
)

/** Whole night at a glance: connected, link drops (at least a few pixels wide to stay visible), unobserved hatched. */
@Composable
fun NightStrip(s: NightSummary, modifier: Modifier) {
    val connected = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.surfaceVariant
    val parts = remember(s) { stretches(s) }
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(base).drawBehind {
            parts.forEach { p ->
                val l = p.from * size.width
                val r = maxOf(p.to * size.width, l + if (p.kind == Watch.DROP) 3.dp.toPx() else 0f)
                when (p.kind) {
                    Watch.CONNECTED -> drawRect(connected, Offset(l, 0f), androidx.compose.ui.geometry.Size(r - l, size.height))
                    Watch.DROP -> drawRect(Amber, Offset(l, 0f), androidx.compose.ui.geometry.Size(r - l, size.height))
                    Watch.UNOBSERVED -> clipRect(l, 0f, r, size.height) {
                        var x = l - size.height
                        while (x < r) {
                            drawLine(Color(0xFF3A2E14), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 4f)
                            x += 8.dp.toPx()
                        }
                    }
                }
            }
        },
    )
}
