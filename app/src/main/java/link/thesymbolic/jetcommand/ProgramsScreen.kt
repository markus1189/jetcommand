package link.thesymbolic.jetcommand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.DeviceId
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.ProgramId
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.SlotStatus
import link.thesymbolic.jetcommand.protocol.SlotView

private sealed interface Route {
    data object List : Route

    data class Detail(val id: ProgramId) : Route

    data class Conflict(val slot: Slot) : Route
}

@Composable
fun ProgramsScreen(source: ProgramsSource, modifier: Modifier = Modifier) {
    var route by remember { mutableStateOf<Route>(Route.List) }
    BackHandler(route != Route.List) { route = Route.List }
    val device by source.device.collectAsState()
    val live by source.live.collectAsState()
    val entries by remember(source) { source.entries() }.collectAsState(emptyList())
    val slots by
        remember(source, device) { device?.let(source::slots) ?: flowOf(emptyList()) }
            .collectAsState(emptyList())
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(source) { source.errors.collect { snackbar.showSnackbar(it) } }
    val scope = rememberCoroutineScope()
    val delete = { e: LibraryEntry -> deleteWithUndo(source, snackbar, scope, e) }
    Box(modifier.fillMaxSize()) {
        when (val r = route) {
            Route.List ->
                ProgramsList(
                    entries,
                    slots,
                    live,
                    open = { route = Route.Detail(it) },
                    review = { route = Route.Conflict(it) },
                    act = { slot -> slotAction(source, slot) },
                    delete = delete,
                )
            // A program deleted or a conflict resolved meanwhile has nothing left to show: back to
            // the list.
            is Route.Detail ->
                entries
                    .firstOrNull { it.id == r.id }
                    ?.let { e ->
                        ProgramDetailScreen(
                            source,
                            e,
                            slots,
                            live,
                            back = { route = Route.List },
                            delete = {
                                route = Route.List
                                delete(e)
                            },
                        )
                    } ?: LaunchedEffect(r) { route = Route.List }
            is Route.Conflict ->
                slots.getOrNull(r.slot.index)?.let { v ->
                    (v.status as? SlotStatus.ChangedOutside)?.let { s ->
                        Conflict(v, s, back = { route = Route.List }) {
                            source.resolve(v.state.slot, it)
                            route = Route.List
                        }
                    }
                } ?: LaunchedEffect(r) { route = Route.List }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun deleteWithUndo(
    source: ProgramsSource,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    e: LibraryEntry,
) {
    source.delete(e.id)
    scope.launch {
        val undo =
            snackbar.showSnackbar(
                "Deleted “${prettyName(e.current.program.name)}”",
                "Undo",
                duration = SnackbarDuration.Long,
            )
        if (undo == SnackbarResult.ActionPerformed) source.restore(e.id)
    }
}

private fun slotAction(source: ProgramsSource, view: SlotView) {
    val slot = view.state.slot
    when (val s = view.status) {
        is SlotStatus.Older -> source.push(slot, s.entry.id)
        is SlotStatus.Interrupted -> source.push(slot, s.target.programId)
        is SlotStatus.Deleted -> source.restore(s.entry.id)
        is SlotStatus.NotInLibrary -> source.keep(slot)
        else -> Unit
    }
}

private fun toneColor(tone: SlotTone): Color =
    when (tone) {
        SlotTone.MUTED -> Muted
        SlotTone.SYNCED -> Sage
        SlotTone.AMBER -> Amber
        SlotTone.WARM -> Warm
    }

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, right: String? = null) =
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        right?.let { Text(it, fontSize = 12.sp, color = Muted) }
    }

@Composable
fun SlotBadge(n: Int, modifier: Modifier = Modifier) =
    Box(
        modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }

@Composable
fun Pill(text: String, fg: Color, border: Color, modifier: Modifier = Modifier) =
    Text(
        text,
        fontSize = 11.sp,
        color = fg,
        modifier =
            modifier
                .border(1.dp, border, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )

@Composable
private fun Check() =
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 24f
        drawPath(
            Path().apply {
                moveTo(5 * u, 12 * u)
                lineTo(10 * u, 17 * u)
                lineTo(19 * u, 8 * u)
            },
            Sage,
            style = Stroke(2 * u),
        )
    }

@Composable
fun ProgramsList(
    entries: List<LibraryEntry>,
    slots: List<SlotView>,
    live: Boolean,
    open: (ProgramId) -> Unit,
    review: (Slot) -> Unit,
    act: (SlotView) -> Unit,
    delete: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
    start: LocalTime = LocalTime.now(),
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Programs", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Text("Your library, and what the BedJet holds.", fontSize = 14.sp, color = Muted)
            }
            // "+ New" waits for the program editor (ROADMAP); hidden rather than a button that does
            // nothing.
        }
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SlotsSection(slots, live, open, review, act)
            LibrarySection(entries, slots, open, delete, start)
        }
    }
}

@Composable
private fun SlotsSection(
    slots: List<SlotView>,
    live: Boolean,
    open: (ProgramId) -> Unit,
    review: (Slot) -> Unit,
    act: (SlotView) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val read = slots.mapNotNull { it.state.readAt }.minOrNull()
        SectionTitle(
            "On the BedJet",
            right =
                listOfNotNull(read?.let { "read ${hhmm(it)}" }, if (live) "live" else "offline")
                    .joinToString(" · "),
        )
        Column(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            if (slots.isEmpty())
                Text(
                    "No BedJet connected yet.",
                    color = Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
            slots.forEachIndexed { i, v ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SlotRow(v, live, open, review, act)
            }
        }
    }
}

@Composable
private fun LibrarySection(
    entries: List<LibraryEntry>,
    slots: List<SlotView>,
    open: (ProgramId) -> Unit,
    delete: (LibraryEntry) -> Unit,
    start: LocalTime,
) {
    val library = entries.filter { it.deletedAt == null }
    var openRow by remember { mutableStateOf<ProgramId?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(
            "Library",
            right = plural(library.size, "program"),
        )
        if (library.isEmpty())
            Text(
                "Programs you keep from the BedJet show up here.",
                color = Muted,
                fontSize = 14.sp,
            )
        library.forEach { e ->
            key(e.id) {
                SwipeToDelete(
                    prettyName(e.current.program.name),
                    isOpen = openRow == e.id,
                    setOpen = {
                        if (it) openRow = e.id else if (openRow == e.id) openRow = null
                    },
                    onDelete = { delete(e) },
                    onClick = { open(e.id) },
                ) { m ->
                    LibraryRow(e, slots, start, m)
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    v: SlotView,
    live: Boolean,
    open: (ProgramId) -> Unit,
    review: (Slot) -> Unit,
    act: (SlotView) -> Unit,
) {
    val line = slotLine(v.status)
    val target: (() -> Unit)? =
        when (val tap = slotTap(v)) {
            is SlotTap.Open -> {
                { open(tap.programId) }
            }
            is SlotTap.Review -> {
                { review(tap.slot) }
            }
            null -> null
        }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .let { if (target != null) it.clickable(onClick = target) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SlotBadge(v.state.slot.number)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(slotName(v), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(line.text, fontSize = 12.sp, color = toneColor(line.tone))
        }
        val action = line.action
        when {
            line.ok -> Check()
            action == SlotAction.REVIEW ->
                Text(
                    action.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            action != null -> {
                val enabled = live || !action.needsUnit
                Text(
                    action.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else Muted,
                    modifier =
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(enabled) { act(v) }
                            .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(
    e: LibraryEntry,
    slots: List<SlotView>,
    start: LocalTime,
    modifier: Modifier = Modifier,
) {
    val p = e.current.program
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                prettyName(p.name),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(programMeta(e.current.rev, p.steps, start), fontSize = 12.sp, color = Muted)
        }
        StepStrip(timeline(p.steps, start), height = 24.dp)
        val badges = libraryBadges(e.id, slots)
        if (badges.isNotEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach {
                    Pill(it, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.outline)
                }
            }
    }
}

@Preview(widthDp = 390, heightDp = 844, backgroundColor = 0xFF07090C, showBackground = true)
@Composable
private fun ProgramsListPreview() = JetCommandTheme {
    val fake = remember { FakePrograms() }
    val entries by fake.entries().collectAsState(emptyList())
    val slots by fake.slots(DeviceId("demo")).collectAsState(emptyList())
    ProgramsList(
        entries,
        slots,
        live = true,
        open = {},
        review = {},
        act = {},
        delete = {},
        start = LocalTime.of(22, 30),
    )
}
