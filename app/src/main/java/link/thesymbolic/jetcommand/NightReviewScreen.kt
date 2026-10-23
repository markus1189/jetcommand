package link.thesymbolic.jetcommand

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.util.Locale
import link.thesymbolic.jetcommand.night.EventRow
import link.thesymbolic.jetcommand.night.NightText
import link.thesymbolic.jetcommand.night.allRows
import link.thesymbolic.jetcommand.night.duration
import link.thesymbolic.jetcommand.night.importantRows
import link.thesymbolic.jetcommand.night.killedCallouts
import link.thesymbolic.jetcommand.night.readout
import link.thesymbolic.jetcommand.night.sampleAt
import link.thesymbolic.jetcommand.night.shareText
import link.thesymbolic.jetcommand.night.stats
import link.thesymbolic.jetcommand.protocol.NightDetail
import link.thesymbolic.jetcommand.protocol.NightSummary
import link.thesymbolic.jetcommand.protocol.NightsSource

private val CalloutBg = Color(0xFF2A2310)
private val CalloutText = Color(0xFFD9CBA8)

@Composable
fun NightReviewScreen(
    source: NightsSource,
    night: NightSummary,
    text: NightText,
    onBack: () -> Unit,
) {
    var detail by remember { mutableStateOf<NightDetail?>(null) }
    LaunchedEffect(night) { detail = source.detail(night) }
    val d = detail
    var important by remember { mutableStateOf(true) }
    val cursor = remember { mutableStateOf<Double?>(null) }
    val context = LocalContext.current
    val tabular = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(BackIcon, contentDescription = "Back to nights")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (night.ongoing) "Tonight" else text.title(night),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text.span(night),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    enabled = d != null,
                    onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    "JetCommand night ${text.title(night)}",
                                )
                                .putExtra(
                                    Intent.EXTRA_TEXT,
                                    shareText(d ?: return@IconButton, text),
                                )
                        context.startActivity(Intent.createChooser(send, "Share night log"))
                    },
                ) {
                    Icon(
                        ShareIcon,
                        contentDescription = "Share log as text",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (d == null) return@LazyColumn
        items(killedCallouts(d, text)) { (title, body) ->
            Column(
                Modifier.fillMaxWidth()
                    .background(CalloutBg, RoundedCornerShape(16.dp))
                    .border(1.dp, ChipWarnBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Amber)
                Text(body, style = MaterialTheme.typography.bodySmall, color = CalloutText)
            }
        }
        item { StatTiles(d) }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Timeline", style = MaterialTheme.typography.titleSmall)
                // Above the chart, so the finger scrubbing it doesn't cover the values.
                val x = cursor.value
                Text(
                    if (x == null) "Touch and drag the chart to read the values at a time."
                    else
                        (night.start + (x * 60_000).toLong()).let {
                            readout(sampleAt(d.samples, it), it, text)
                        },
                    style = tabular,
                    color =
                        if (x == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    minLines = 2,
                )
                NightTimeline(d, text, cursor)
                Legend()
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Events",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                listOf("Important" to true, "All" to false).forEach { (label, value) ->
                    FilterChip(
                        selected = important == value,
                        onClick = { important = value },
                        label = { Text(label) },
                        modifier = Modifier.padding(start = 6.dp),
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    )
                }
            }
        }
        val rows = if (important) importantRows(d, text) else allRows(d)
        items(rows) { EventLine(it, text, seconds = !important) }
    }
}

@Composable
private fun StatTiles(d: NightDetail) {
    val st = remember(d) { stats(d) }
    val tiles =
        listOf(
            duration(st.watched) to "watched of ${duration(st.total)}",
            "${st.drops}" to
                if (st.drops == 0) "drops" else "drops · longest ${duration(st.longestDrop ?: 0)}",
            "${st.presses}" to
                if (st.pressSplit.isEmpty()) "presses" else "presses · ${st.pressSplit}",
            (st.deviation?.let { "±" + String.format(Locale.ROOT, "%.1f", it) + " °C" } ?: "--") to
                "room vs target, average",
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (value, label) ->
                    Column(
                        Modifier.weight(1f)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(12.dp)
                    ) {
                        Text(value, style = MaterialTheme.typography.titleLarge)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFFC8CDD3))) { append("— room   ") }
            withStyle(SpanStyle(color = Color(0xFF8FB4D9))) { append("- - target   ") }
            withStyle(SpanStyle(color = Color(0xFF4C9AFF))) { append("— air out   ") }
            withStyle(SpanStyle(color = muted)) { append("● press   ") }
            withStyle(SpanStyle(color = Amber)) { append("▮ drop   ") }
            withStyle(SpanStyle(color = muted)) { append("▨ not recorded") }
        },
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun EventLine(r: EventRow, text: NightText, seconds: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.Top) {
        Text(
            r.at?.let { if (seconds) text.timeSec(it) else text.time(it) } ?: "·",
            Modifier.width(if (seconds) 72.dp else 56.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = if (r.muted) MaterialTheme.colorScheme.outline else muted,
        )
        Text(
            buildAnnotatedString {
                r.lead?.let { withStyle(SpanStyle(color = Amber)) { append(it) } }
                append(r.text)
                r.note?.let { withStyle(SpanStyle(color = muted)) { append(it) } }
            },
            style = MaterialTheme.typography.bodyMedium,
            color =
                when {
                    r.warn -> Amber
                    r.muted -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

private fun strokeIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        .build()

private val BackIcon =
    strokeIcon("Back") {
        moveTo(15f, 5f)
        lineTo(8f, 12f)
        lineTo(15f, 19f)
    }

private val ShareIcon =
    strokeIcon("Share") {
        fun circle(cx: Float, cy: Float) {
            moveTo(cx + 3f, cy)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
        }
        circle(18f, 5f)
        circle(6f, 12f)
        circle(18f, 19f)
        moveTo(8.6f, 13.5f)
        lineTo(15.4f, 17.5f)
        moveTo(15.4f, 6.5f)
        lineTo(8.6f, 10.5f)
    }
