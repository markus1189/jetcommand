package link.thesymbolic.jetcommand.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.Amber
import link.thesymbolic.jetcommand.FakePrograms
import link.thesymbolic.jetcommand.JetCommandTheme
import link.thesymbolic.jetcommand.Muted
import link.thesymbolic.jetcommand.OnModeFill
import link.thesymbolic.jetcommand.ProgramsSource
import link.thesymbolic.jetcommand.Refused
import link.thesymbolic.jetcommand.ScrubLine
import link.thesymbolic.jetcommand.SectionTitle
import link.thesymbolic.jetcommand.StepStrip
import link.thesymbolic.jetcommand.TickLabel
import link.thesymbolic.jetcommand.TrashIcon
import link.thesymbolic.jetcommand.Warm
import link.thesymbolic.jetcommand.WarmLine
import link.thesymbolic.jetcommand.modeColor
import link.thesymbolic.jetcommand.prettyName
import link.thesymbolic.jetcommand.protocol.EDITOR_MODES
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.StepEdit
import link.thesymbolic.jetcommand.protocol.Timing
import link.thesymbolic.jetcommand.protocol.startWindows
import link.thesymbolic.jetcommand.protocol.untilAllowed
import link.thesymbolic.jetcommand.stripWidths

/** Edits [entry]'s current revision; [back] returns to the program, saved or not. */
@Composable
fun EditorScreen(
    source: ProgramsSource,
    entry: LibraryEntry,
    back: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revisions by
        produceState(emptyList<ProgramRevision>(), entry.id) { value = source.revisions(entry.id) }
    val next = nextRev(entry.current.rev, revisions)
    // Keyed on the id: saving updates the entry, which must not reset the edits mid-save.
    var state by remember(entry.id) { mutableStateOf(editorFor(entry.current)) }
    var leaving by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save = {
        if (!saving) {
            saving = true
            scope.launch {
                source.saveEdit(entry.id, Program(state.name, state.steps))
                back()
            }
        }
    }
    BackHandler { if (state.changes > 0) leaving = true else back() }
    Editor(
        state,
        SaveBar(next, saving, back = { if (state.changes > 0) leaving = true else back() }) {
            save()
        },
        update = { state = it },
        modifier = modifier,
    )
    if (leaving)
        LeaveSheet(
            leaveText(state, next),
            saveLabel(next),
            save = {
                leaving = false
                save()
            },
            discard = back,
            stay = { leaving = false },
        )
}

/** A strip tap or a new step asks for its row to scroll into view; [n] repeats a request. */
private data class Reveal(val index: Int, val n: Long)

/** The top row's state and actions. */
data class SaveBar(
    val nextRev: Int,
    val saving: Boolean,
    val back: () -> Unit,
    val save: () -> Unit,
)

@Composable
fun Editor(
    state: EditorState,
    bar: SaveBar,
    update: (EditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reveal by remember { mutableStateOf<Reveal?>(null) }
    val open = { i: Int ->
        update(state.open(i))
        reveal = Reveal(i, System.nanoTime())
    }
    // A step that becomes the open one (added, duplicated, expanded) scrolls into view.
    val change: (EditorState) -> Unit = { s ->
        update(s)
        val opened = s.selected
        if (opened != null && opened != state.selected) reveal = Reveal(opened, System.nanoTime())
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopRow(state, bar) { update(state.discard()) }
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(prettyName(state.name), fontSize = 26.sp, fontWeight = FontWeight.Medium)
            TapStrip(state, open)
            StartCard(state, change)
        }
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stepsTitle(state.steps.size))
            StepsCard(state, change, reveal)
        }
    }
}

@Composable
private fun TopRow(state: EditorState, bar: SaveBar, discard: () -> Unit) {
    val changed = state.changes > 0
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = bar.back)
                .semantics { contentDescription = "Back to program" },
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", fontSize = 26.sp)
        }
        Text(
            statusText(state.rev, state.changes),
            fontSize = 13.sp,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        val pad = PaddingValues(horizontal = 14.dp)
        OutlinedButton(
            discard,
            Modifier.heightIn(min = 48.dp),
            enabled = changed,
            contentPadding = pad,
        ) {
            Text("Discard", fontSize = 14.sp)
        }
        Button(
            bar.save,
            Modifier.heightIn(min = 48.dp),
            enabled = changed && !bar.saving,
            contentPadding = pad,
        ) {
            Text(saveLabel(bar.nextRev), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** [StepStrip] with a button over each block; blocks are as wide as the strip draws them. */
@Composable
private fun TapStrip(state: EditorState, open: (Int) -> Unit) {
    val spans =
        remember(state.steps, state.start) { spans(state.steps, state.planned, state.start) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        StepStrip(spans, height = 40.dp)
        val d = LocalDensity.current
        val gap = with(d) { 2.dp.toPx() }
        val avail = with(d) { maxWidth.toPx() } - gap * (spans.size - 1)
        val widths = stripWidths(spans.map { it.minutes }, avail, with(d) { 4.dp.toPx() })
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            spans.forEachIndexed { i, s ->
                val on = state.selected == i
                Box(
                    Modifier.width(with(d) { widths[i].toDp() })
                        .fillMaxHeight()
                        .let {
                            if (on) it.border(2.dp, ScrubLine, RoundedCornerShape(4.dp)) else it
                        }
                        .clickable(role = Role.Button) { open(i) }
                        .semantics { contentDescription = stripLabel(i, s.step) }
                )
            }
        }
    }
}

@Composable
private fun StartCard(state: EditorState, update: (EditorState) -> Unit) {
    val windows = remember(state.steps) { startWindows(state.steps) }
    val text = windowText(state.steps, windows, state.planned, state.start)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("If started at", fontSize = 14.sp, color = Muted)
            PlusMinus("−", "Start 15 minutes earlier") { update(state.shiftStart(-START_STEP)) }
            Text(clock(state.start), fontSize = 18.sp)
            PlusMinus("+", "Start 15 minutes later") { update(state.shiftStart(START_STEP)) }
            Text(
                "ends ${clock(state.planned.lastOrNull()?.to ?: state.start)}",
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (text.refused) Refused else MaterialTheme.colorScheme.onSurface,
        )
        Text(text.sub, fontSize = 12.sp, color = Muted, lineHeight = 17.sp)
    }
}

@Composable
private fun PlusMinus(
    symbol: String,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) =
    OutlinedIconButton(
        onClick,
        Modifier.size(48.dp).semantics { contentDescription = label },
        enabled = enabled,
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.35f),
            ),
    ) {
        Text(symbol, fontSize = 20.sp)
    }

/** A drag handle's gesture callbacks; [by] gets the vertical movement in px. */
private class Grip(
    val start: () -> Unit,
    val by: (Float) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
)

private data class Drag(val from: Int, val offset: Float, val mids: List<Float>) {
    val slot
        get() = insertionSlot(mids, mids[from] + offset)
}

@Composable
private fun StepsCard(
    state: EditorState,
    update: (EditorState) -> Unit,
    reveal: Reveal?,
) {
    var drag by remember { mutableStateOf<Drag?>(null) }
    // Measured every layout; read once when a drag starts.
    val mids = remember { HashMap<Int, Float>() }
    // The handles' gesture detectors outlive recompositions; they act on the latest state.
    val current by rememberUpdatedState(state)
    val currentUpdate by rememberUpdatedState(update)
    val n = state.steps.size
    Column(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        state.steps.forEachIndexed { i, _ ->
            val d = drag
            Box(
                Modifier.onGloballyPositioned {
                        mids[i] = it.positionInParent().y + it.size.height / 2f
                    }
                    .zIndex(if (d?.from == i) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (d?.from == i) d.offset else 0f
                        alpha = if (d?.from == i) 0.85f else 1f
                    }
                    .dropLine(d != null && d.slot == i && i != d.from && i != d.from + 1)
            ) {
                StepItem(
                    state,
                    i,
                    update,
                    reveal,
                    Grip(
                        start = { drag = Drag(i, 0f, List(n) { k -> mids[k] ?: 0f }) },
                        by = { dy -> drag = drag?.let { it.copy(offset = it.offset + dy) } },
                        end = {
                            drag?.let { g ->
                                currentUpdate(current.move(g.from, dropTarget(g.from, g.slot)))
                            }
                            drag = null
                        },
                        cancel = { drag = null },
                    ),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
        val d = drag
        AddRow(state.full, Modifier.dropLine(d != null && d.slot == n && d.from != n - 1)) {
            update(state.add())
        }
    }
}

private fun Modifier.dropLine(show: Boolean) =
    if (!show) this
    else
        drawWithContent {
            drawContent()
            drawRect(ScrubLine, Offset.Zero, Size(size.width, 3.dp.toPx()))
        }

@Composable
private fun AddRow(full: Boolean, modifier: Modifier = Modifier, add: () -> Unit) =
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = !full, role = Role.Button, onClick = add),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (full) "A program holds 32 steps" else "+ Add step",
            fontSize = 15.sp,
            color = if (full) Muted else MaterialTheme.colorScheme.primary,
        )
    }

@Composable
private fun StepItem(
    state: EditorState,
    i: Int,
    update: (EditorState) -> Unit,
    reveal: Reveal?,
    grip: Grip,
) {
    val open = state.selected == i
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal?.index == i) {
            // One frame, so the expanded editor is laid out before the scroll measures it.
            withFrameNanos {}
            requester.bringIntoView()
        }
    }
    Column(
        Modifier.bringIntoViewRequester(requester)
            .background(if (open) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
    ) {
        StepHeader(
            state,
            i,
            grip,
            toggle = { update(state.toggle(i)) },
            move = { delta ->
                update(state.move(i, i + delta))
                true
            },
        )
        issues(state.steps, state.planned)[i]?.let {
            Text(
                it.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (it.refused) Refused else Amber,
                modifier = Modifier.padding(start = 50.dp, end = 16.dp, bottom = 10.dp),
            )
        }
        if (open) StepEditor(state, i, update)
    }
}

@Composable
private fun StepHeader(
    state: EditorState,
    i: Int,
    grip: Grip,
    toggle: () -> Unit,
    move: (Int) -> Boolean,
) {
    val step = state.steps[i]
    val n = state.steps.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f)
                .heightIn(min = 60.dp)
                .clickable(
                    onClickLabel = if (state.selected == i) "Collapse" else "Edit",
                    onClick = toggle,
                )
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${i + 1}", Modifier.width(22.dp), fontSize = 13.sp, color = Muted)
            Box(Modifier.size(10.dp).clip(CircleShape).background(modeColor(step.mode)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(step.mode.label, fontSize = 15.sp)
                Text(detailText(step), fontSize = 12.sp, color = Muted)
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(lengthText(step), fontSize = 14.sp)
                Text("ends ${clock(state.planned[i].to)}", fontSize = 12.sp, color = Muted)
            }
        }
        Box(
            Modifier.size(48.dp)
                .pointerInput(i, n) {
                    detectDragGestures(
                        onDragStart = { grip.start() },
                        onDragEnd = grip.end,
                        onDragCancel = grip.cancel,
                    ) { ev, amount ->
                        ev.consume()
                        grip.by(amount.y)
                    }
                }
                .semantics {
                    contentDescription = "Reorder step ${i + 1}"
                    customActions =
                        listOfNotNull(
                            CustomAccessibilityAction("Move up") { move(-1) }.takeIf { i > 0 },
                            CustomAccessibilityAction("Move down") { move(1) }.takeIf { i < n - 1 },
                        )
                },
            contentAlignment = Alignment.Center,
        ) {
            GripIcon()
        }
    }
}

@Composable
private fun GripIcon() =
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 24f
        for (x in listOf(9f, 15f)) for (y in listOf(6f, 12f, 18f)) {
            drawCircle(TickLabel, 1.6f * u, Offset(x * u, y * u))
        }
    }

@Composable
private fun StepEditor(state: EditorState, i: Int, update: (EditorState) -> Unit) {
    val step = state.steps[i]
    val edit = { e: StepEdit -> update(state.edit(i, e)) }
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ModeChips(step.mode) { edit(StepEdit.SetMode(it)) }
        Setting(
            Control("Fan", fanText(step), "Less fan", "More fan", fanBy(step, -1), fanBy(step, 1)),
            edit,
        )
        Setting(
            Control(
                "Temp",
                tempText(step),
                "Cooler",
                "Warmer",
                tempBy(step, -1),
                tempBy(step, 1),
                tempRangeText(step.mode),
            ),
            edit,
        )
        TimingRow(step.mode, step.timing is Timing.Until, edit)
        Setting(
            Control(
                "",
                timeText(step),
                "Shorter or earlier",
                "Longer or later",
                shorter(step),
                longer(step),
                timeHint(step, state.planned[i]),
            ),
            edit,
        )
        state.note
            ?.takeIf { it.index == i }
            ?.let { Text(it.text, fontSize = 13.sp, lineHeight = 18.sp, color = ScrubLine) }
        StepActions(state, i, update)
    }
}

@Composable
private fun StepActions(state: EditorState, i: Int, update: (EditorState) -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            { update(state.duplicate(i)) },
            Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = !state.full,
        ) {
            Text("Duplicate", fontSize = 14.sp)
        }
        OutlinedButton(
            { update(state.delete(i)) },
            Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = !state.single,
            border = BorderStroke(1.dp, WarmLine),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Warm),
        ) {
            TrashIcon(18.dp, color = if (state.single) Muted else Warm)
            Spacer(Modifier.width(8.dp))
            Text("Delete", fontSize = 14.sp)
        }
    }

@Composable
private fun ModeChips(mode: Mode, pick: (Mode) -> Unit) =
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EDITOR_MODES.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    val on = m == mode
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(shape)
                            .let {
                                if (on) it.background(modeColor(m))
                                else it.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                            }
                            .selectable(on, role = Role.RadioButton) { pick(m) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.label,
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) OnModeFill else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

/** One −/+ row; a null edit disables its button. */
private data class Control(
    val label: String,
    val value: String,
    val downLabel: String,
    val upLabel: String,
    val down: StepEdit?,
    val up: StepEdit?,
    val sub: String = "",
)

@Composable
private fun Setting(c: Control, edit: (StepEdit) -> Unit) =
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(c.label, Modifier.width(56.dp), fontSize = 14.sp, color = Muted)
        PlusMinus("−", c.downLabel, enabled = c.down != null) { c.down?.let(edit) }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(c.value, fontSize = 16.sp)
            if (c.sub.isNotEmpty())
                Text(c.sub, fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center)
        }
        PlusMinus("+", c.upLabel, enabled = c.up != null) { c.up?.let(edit) }
    }

@Composable
private fun TimingRow(mode: Mode, until: Boolean, edit: (StepEdit) -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Time", Modifier.width(56.dp), fontSize = 14.sp, color = Muted)
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = !until,
                    onClick = { edit(StepEdit.ToDuration) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = segmentColors(),
                    icon = {},
                ) {
                    Text("Run for", fontSize = 14.sp)
                }
                SegmentedButton(
                    selected = until,
                    onClick = { edit(StepEdit.ToUntil) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = segmentColors(),
                    enabled = untilAllowed(mode),
                    icon = {},
                ) {
                    Text("Run until", fontSize = 14.sp)
                }
            }
        }
        untilWhy(mode)?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 66.dp),
            )
        }
    }

@Composable
private fun segmentColors() =
    SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        inactiveContainerColor = Color.Transparent,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveSheet(
    text: String,
    saveLabel: String,
    save: () -> Unit,
    discard: () -> Unit,
    stay: () -> Unit,
) =
    ModalBottomSheet(stay, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Keep your changes?", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 14.sp, color = Muted, lineHeight = 21.sp)
            val full = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            Button(save, full) {
                Text(saveLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                discard,
                full,
                border = BorderStroke(1.dp, WarmLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Warm),
            ) {
                Text("Discard changes", fontSize = 15.sp)
            }
            OutlinedButton(stay, full) { Text("Keep editing", fontSize = 15.sp) }
        }
    }

@Preview(widthDp = 390, heightDp = 1500, backgroundColor = 0xFF07090C, showBackground = true)
@Composable
private fun EditorPreview() = JetCommandTheme {
    val f = remember { FakePrograms() }
    var s by remember { mutableStateOf(editorFor(f.revs.getValue("summer").first()).open(3)) }
    Editor(s, SaveBar(7, saving = false, back = {}, save = {}), update = { s = it })
}
