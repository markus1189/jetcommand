package link.thesymbolic.jetcommand

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.Resolution
import link.thesymbolic.jetcommand.protocol.SlotStatus
import link.thesymbolic.jetcommand.protocol.SlotView
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.formatTemp

private val Warm = Color(0xFFF0A070)
private val AmberBg = Color(0xFF2A2213)
private val AmberLine = Color(0xFF5A4520)
private val AmberText = Color(0xFFC8B48A)
private val WarmBg = Color(0xFF2A1D14)
private val WarmLine = Color(0xFF5A3420)

@Composable
private fun BackRow(back: () -> Unit) =
    Row(
        Modifier.padding(start = 8.dp, top = 12.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = back)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Text("‹", fontSize = 26.sp)
        }
        Text("Programs", fontSize = 14.sp, color = Muted)
    }

@Composable
private fun Card(content: @Composable () -> Unit) =
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        content()
    }

private enum class PickFor {
    RUN,
    LOAD,
}

@Composable
fun ProgramDetailScreen(
    source: ProgramsSource,
    entry: LibraryEntry,
    slots: List<SlotView>,
    live: Boolean,
    back: () -> Unit,
    delete: () -> Unit,
) {
    val revisions by
        produceState(emptyList<ProgramRevision>(), entry) { value = source.revisions(entry.id) }
    var picker by remember { mutableStateOf<PickFor?>(null) }
    ProgramDetail(
        entry,
        revisions,
        slots,
        live,
        back = back,
        update = { source.push(it, entry.id) },
        run = { picker = PickFor.RUN },
        load = { picker = PickFor.LOAD },
        delete = delete,
    )
    picker?.let { mode ->
        val device by source.device.collectAsState()
        val ranked by
            produceState(listOf(0, 1, 2), entry, device) {
                device?.let { d ->
                    value = runCatching { source.rankSlots(d, entry.id) }.getOrDefault(value)
                }
            }
        SlotPicker(entry, slots, ranked, mode, dismiss = { picker = null }) { slot ->
            picker = null
            if (mode == PickFor.RUN) source.run(slot, entry.id) else source.push(slot, entry.id)
        }
    }
}

@Composable
fun ProgramDetail(
    entry: LibraryEntry,
    revisions: List<ProgramRevision>,
    slots: List<SlotView>,
    live: Boolean,
    back: () -> Unit,
    update: (Int) -> Unit,
    run: () -> Unit,
    load: () -> Unit,
    delete: () -> Unit = {},
    start: LocalTime = LocalTime.now(),
) {
    val p = entry.current.program
    val spans = remember(p, start) { timeline(p.steps, start) }
    val end = spans.lastOrNull()?.to ?: 0
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BackRow(back)
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(prettyName(p.name), fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                        "r${entry.current.rev}".takeIf { entry.current.rev > 1 },
                        "${p.steps.size} steps",
                        "from ${clockAt(start, 0)} until ${clockAt(start, end)}",
                    )
                    .joinToString(" · "),
                fontSize = 14.sp,
                color = Muted,
            )
            StepStrip(spans, Modifier.padding(top = 8.dp), height = 36.dp)
        }
        slots.forEach { v ->
            val s = v.status as? SlotStatus.Older ?: return@forEach
            if (s.entry.id != entry.id) return@forEach
            Row(
                Modifier.padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AmberBg)
                    .border(1.dp, AmberLine, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val n = v.state.slot + 1
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Slot $n still holds r${s.slotRev}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Amber,
                    )
                    val edits = entry.current.rev - s.slotRev
                    Text(
                        "$edits edit${if (edits == 1) "" else "s"} since you last loaded it",
                        fontSize = 12.sp,
                        color = AmberText,
                    )
                }
                OutlinedButton(
                    { update(v.state.slot) },
                    enabled = live,
                    border = BorderStroke(1.dp, AmberLine),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                ) {
                    Text("Update slot $n")
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val h = Modifier.weight(1f).heightIn(min = 48.dp)
            Button(run, h, enabled = live) {
                Text("Run", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(load, h, enabled = live) { Text("Load…", fontSize = 15.sp) }
            // The program editor comes later (ROADMAP); shown so the layout is the final one.
            OutlinedButton({}, h, enabled = false) { Text("Edit", fontSize = 15.sp) }
            val name = prettyName(p.name)
            Box(
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color(0xFF5A3420), CircleShape)
                    .clickable(onClick = delete)
                    .semantics { contentDescription = "Delete $name" },
                contentAlignment = Alignment.Center,
            ) {
                TrashIcon(20.dp)
            }
        }
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Steps")
            Box(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                ProgramChart(spans, start)
            }
            Card {
                spans.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    StepRow(s, start)
                }
            }
        }
        if (revisions.isNotEmpty())
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle("History")
                Card {
                    revisions.forEachIndexed { i, r ->
                        if (i > 0)
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        RevisionRow(r, entry, slots)
                    }
                }
            }
    }
}

@Composable
private fun StepRow(s: Span, start: LocalTime) =
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(modeColor(s.step.mode)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.step.mode.label, fontSize = 15.sp)
            Text(stepListDetail(s.step), fontSize = 12.sp, color = Muted)
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                if (s.step.clock) "until ${clockAt(start, s.to)}" else duration(s.minutes),
                fontSize = 14.sp,
            )
            Text("ends ${clockAt(start, s.to)}", fontSize = 12.sp, color = Muted)
        }
    }

private val When = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

fun originText(o: Origin) =
    when (o) {
        Origin.BLANK -> "Created"
        Origin.TEMPLATE -> "From a template"
        Origin.EDIT -> "Edited"
        Origin.KEPT_FROM_SLOT -> "Kept from the BedJet"
        Origin.OUTSIDE -> "Read from the BedJet: changed in another app"
    }

@Composable
private fun RevisionRow(r: ProgramRevision, entry: LibraryEntry, slots: List<SlotView>) =
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "r${r.rev}",
            Modifier.width(32.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Muted,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.note ?: originText(r.origin), fontSize = 14.sp)
            Text(
                When.format(Instant.ofEpochMilli(r.createdAt).atZone(ZoneId.systemDefault())),
                fontSize = 12.sp,
                color = Muted,
            )
        }
        val onSlot = slots.firstOrNull { v ->
            when (val s = v.status) {
                is SlotStatus.InSync -> s.entry.id == entry.id && r.rev == entry.current.rev
                is SlotStatus.Older -> s.entry.id == entry.id && s.slotRev == r.rev
                else -> false
            }
        }
        when {
            r.rev == entry.current.rev ->
                Pill(
                    "current",
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    MaterialTheme.colorScheme.primaryContainer,
                )
            onSlot != null -> Pill("on slot ${onSlot.state.slot + 1}", Amber, AmberLine)
            r.origin == Origin.OUTSIDE -> Pill("outside", Warm, WarmLine)
        }
    }

/** The consequence of writing [entry] over [v], as the picker words it; true when it warns. */
private fun pickNote(v: SlotView, entry: LibraryEntry): Pair<String, Boolean> =
    when (val s = v.status) {
        is SlotStatus.InSync ->
            if (s.entry.id == entry.id) "Already loaded · runs as it is" to false
            else "In sync · stays in your library" to false
        is SlotStatus.Older ->
            if (s.entry.id == entry.id) "Older version · r${s.slotRev} stays in history" to false
            else "Older version · stays in your library" to false
        SlotStatus.Empty -> "Empty" to false
        SlotStatus.Unknown -> "Not read yet · read before writing" to false
        is SlotStatus.ChangedOutside ->
            "Changed outside the app · saved to your library first" to true
        SlotStatus.NotInLibrary -> "Not in your library · saved to your library first" to true
        is SlotStatus.Deleted -> "Deleted from library · stays restorable" to false
        is SlotStatus.Interrupted -> "Write interrupted" to true
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotPicker(
    entry: LibraryEntry,
    slots: List<SlotView>,
    ranked: List<Int>,
    mode: PickFor,
    dismiss: () -> Unit,
    go: (Int) -> Unit,
) {
    ModalBottomSheet(dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        SlotPickerBody(entry, slots, ranked, mode == PickFor.RUN, go)
    }
}

@Composable
fun SlotPickerBody(
    entry: LibraryEntry,
    slots: List<SlotView>,
    ranked: List<Int>,
    run: Boolean,
    go: (Int) -> Unit,
) {
    var picked by remember(ranked) { mutableIntStateOf(ranked.firstOrNull() ?: 0) }
    val name = prettyName(entry.current.program.name)
    val holds = slots.any { (it.status as? SlotStatus.InSync)?.entry?.id == entry.id }
    val older = slots.firstOrNull { (it.status as? SlotStatus.Older)?.entry?.id == entry.id }
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${if (run) "Run" else "Load"} $name",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                when {
                    holds -> "It's on the BedJet already."
                    older != null ->
                        "Slot ${older.state.slot + 1} holds an older version. Which slot gets this one?"
                    else -> "It isn't on the BedJet yet. Which slot does it replace?"
                },
                fontSize = 14.sp,
                color = Muted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ranked
                .mapNotNull { n -> slots.getOrNull(n) }
                .forEachIndexed { i, v ->
                    val on = v.state.slot == picked
                    val (note, warn) = pickNote(v, entry)
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (on) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.background
                            )
                            .border(
                                2.dp,
                                if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(16.dp),
                            )
                            .clickable { picked = v.state.slot }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SlotBadge(v.state.slot + 1)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val rev =
                                (v.status as? SlotStatus.Older)?.slotRev?.let { " r$it" } ?: ""
                            Text(
                                slotName(v) + rev,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(note, fontSize = 12.sp, color = if (warn) Warm else Muted)
                        }
                        if (i == 0)
                            Text(
                                "suggested",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier =
                                    Modifier.clip(RoundedCornerShape(999.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                    }
                }
        }
        val pickedHolds =
            (slots.getOrNull(picked)?.status as? SlotStatus.InSync)?.entry?.id == entry.id
        Button({ go(picked) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(
                when {
                    run && pickedHolds -> "Run from slot ${picked + 1}"
                    run -> "Load into slot ${picked + 1} and run"
                    else -> "Load into slot ${picked + 1}"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            when {
                run && pickedHolds -> "Sets the clock, then starts."
                run -> "Writes, reads back to check, sets the clock, then starts."
                else -> "Writes, then reads back to check."
            },
            fontSize = 12.sp,
            color = Muted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ConflictScreen(source: ProgramsSource, v: SlotView, back: () -> Unit) {
    val s = v.status as SlotStatus.ChangedOutside
    val revisions by
        produceState(emptyList<ProgramRevision>(), s.entry.id, s.outsideRev) {
            value = source.revisions(s.entry.id)
        }
    Conflict(
        v,
        s,
        revisions.firstOrNull { it.rev == s.outsideRev }?.program?.steps
            ?: v.state.program?.steps.orEmpty(),
        back,
    ) {
        source.resolve(v.state.slot, it)
        back()
    }
}

private fun stepListDetail(s: Step): String =
    when (s.mode) {
        Mode.TURBO -> "${stepDetail(s)} · ${formatTemp(s.temp)} fixed"
        Mode.WAIT -> "${stepDetail(s)} · no temperature"
        else -> stepDetail(s)
    }

private fun stepCell(s: Step?): Pair<String, String> =
    if (s == null) "" to "—"
    else
        s.mode.label to
            listOfNotNull(
                    stepDetail(s).takeIf { s.mode != Mode.WAIT },
                    if (s.clock) "until %02d:%02d".format(s.hours, s.minutes)
                    else duration(s.totalMinutes),
                )
                .joinToString(" · ")

@Composable
fun Conflict(
    v: SlotView,
    s: SlotStatus.ChangedOutside,
    theirs: List<Step>,
    back: () -> Unit,
    resolve: (Resolution) -> Unit,
) {
    val n = v.state.slot + 1
    val mine = s.entry.current
    val name = prettyName(mine.program.name)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BackRow(back)
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Slot $n changed outside the app",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            val loaded = v.state.pushed?.rev ?: mine.rev
            val read = v.state.readAt?.let { " Read ${hhmm(it)}, the" } ?: " The"
            Text(
                "You loaded $name r$loaded.$read slot holds something else. It's saved as r${s.outsideRev} (outside), so nothing is lost whichever you pick.",
                fontSize = 14.sp,
                color = Muted,
                lineHeight = 20.sp,
            )
        }
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { SectionTitle("Your library · r${mine.rev}") }
                Box(Modifier.weight(1f)) { SectionTitle("On the BedJet · r${s.outsideRev}") }
            }
            val rows = maxOf(mine.program.steps.size, theirs.size)
            var differ = 0
            for (i in 0 until rows) {
                val a = mine.program.steps.getOrNull(i)
                val b = theirs.getOrNull(i)
                val changed = a != b
                if (changed) differ++
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(a, b).forEach { st ->
                        val (l1, l2) = stepCell(st)
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (changed) WarmBg
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .border(
                                    1.dp,
                                    if (changed) WarmLine
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(l1, fontSize = 13.sp)
                            Text(l2, fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
            val sameName = v.state.program?.name == mine.program.name
            Text(
                "$differ step${if (differ == 1) "" else "s"} differ · ${if (sameName) "same name" else "renamed to ${v.state.program?.name?.let(::prettyName)}"}",
                fontSize = 12.sp,
                color = Muted,
            )
        }
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Choice(
                "Take the BedJet's version",
                "r${s.outsideRev} becomes current; the slot is in sync",
                primary = true,
            ) {
                resolve(Resolution.TAKE_THEIRS)
            }
            Choice(
                "Keep mine",
                "r${mine.rev} stays current; slot $n shows as older until you update it",
            ) {
                resolve(Resolution.KEEP_MINE)
            }
            Choice(
                "Split off as a new program",
                "It was something different: slot $n links to the new one",
            ) {
                resolve(Resolution.SPLIT_OFF)
            }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .let {
                if (primary) it.background(MaterialTheme.colorScheme.primary)
                else it.border(1.dp, MaterialTheme.colorScheme.outline, shape)
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        val fg =
            if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
        )
        Text(sub, fontSize = 12.sp, color = if (primary) fg else Muted)
    }
}

private val PreviewStart: LocalTime = LocalTime.of(22, 30)

@Preview(widthDp = 390, heightDp = 1500, backgroundColor = 0xFF07090C, showBackground = true)
@Composable
private fun DetailPreview() = JetCommandTheme {
    val f = remember { FakePrograms() }
    val e = LibraryEntry("summer", f.revs.getValue("summer").first())
    val slots by f.slots("demo").collectAsState(emptyList())
    ProgramDetail(e, f.revs.getValue("summer"), slots, true, {}, {}, {}, {}, start = PreviewStart)
}

@Preview(widthDp = 390, backgroundColor = 0xFF101418, showBackground = true)
@Composable
private fun PickerPreview() = JetCommandTheme {
    val f = remember { FakePrograms() }
    val e = LibraryEntry("hot", f.revs.getValue("hot").first())
    val slots by f.slots("demo").collectAsState(emptyList())
    SlotPickerBody(e, slots, listOf(1, 0, 2), run = true) {}
}
