package link.thesymbolic.jetcommand

import link.thesymbolic.jetcommand.protocol.StartedSlot
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import link.thesymbolic.jetcommand.protocol.BedJetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.LogLevel
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.ProgramLibrary
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.PushRecord
import link.thesymbolic.jetcommand.protocol.Resolution
import link.thesymbolic.jetcommand.protocol.SlotState
import link.thesymbolic.jetcommand.protocol.SlotStatus
import link.thesymbolic.jetcommand.protocol.SlotView
import link.thesymbolic.jetcommand.protocol.Step
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Everything the program screens read or do; [LibraryPrograms] in the app, [FakePrograms] for previews and the emulator. */
interface ProgramsSource {
    /** The device whose slots are shown; null before any was used. */
    val device: StateFlow<String?>
    /** A link is up, so slot actions can reach the unit. */
    val live: StateFlow<Boolean>
    /** Failed actions, as text for the user. */
    val errors: SharedFlow<String>
    val started: StateFlow<StartedSlot?>
    fun entries(): Flow<List<LibraryEntry>>
    fun slots(device: String): Flow<List<SlotView>>
    suspend fun revisions(programId: String): List<ProgramRevision>
    suspend fun rankSlots(device: String, programId: String): List<Int>
    fun run(slot: Int, programId: String)
    fun push(slot: Int, programId: String)
    fun resolve(slot: Int, resolution: Resolution)
    fun keep(slot: Int)
    fun restore(programId: String)
}

/** The real source: [ProgramLibrary] for state, [BedJetController.act] for anything that reaches the unit. */
class LibraryPrograms(
    private val library: ProgramLibrary,
    private val controller: BedJetController,
    private val scope: CoroutineScope,
) : ProgramsSource {
    // The remembered unit keeps its cached slots visible while disconnected.
    override val device: StateFlow<String?> = MutableStateFlow<String?>(null).also { out ->
        scope.launch {
            combine(controller.deviceId, controller.remembered) { id, r -> id ?: r?.address }.collect { out.value = it }
        }
    }
    override val live: StateFlow<Boolean> = MutableStateFlow(false).also { out ->
        scope.launch { controller.deviceId.collect { out.value = it != null } }
    }

    override fun entries() = library.entries()
    override fun slots(device: String) = library.slots(device)
    override suspend fun revisions(programId: String) = library.revisions(programId)
    override suspend fun rankSlots(device: String, programId: String) = library.rankSlots(device, programId)

    override val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val started = library.started

    private fun onUnit(what: String, block: suspend ProgramLibrary.(String, BedJetStack) -> Unit) {
        val id = controller.deviceId.value ?: return controller.log(LogLevel.ERROR, "$what: no device")
        controller.act(what) { stack ->
            try {
                library.block(id, stack)
            } catch (e: BedJetException) {
                errors.tryEmit(e.message ?: e.code.text)
                throw e
            }
        }
    }

    override fun run(slot: Int, programId: String) = onUnit("run program in slot ${slot + 1}") { id, s -> run(id, s.device, slot, programId) }
    override fun push(slot: Int, programId: String) = onUnit("load slot ${slot + 1}") { id, s -> push(id, s.device, slot, programId) }

    private fun local(what: String, block: suspend ProgramLibrary.(String) -> Unit) {
        val id = device.value ?: return
        scope.launch {
            runCatching { library.block(id) }.onFailure {
                if (it is CancellationException) throw it
                controller.log(LogLevel.ERROR, "$what: ${it::class.simpleName} ${it.message}")
                errors.tryEmit("Couldn't $what.")
            }
        }
    }

    override fun resolve(slot: Int, resolution: Resolution) = local("resolve slot ${slot + 1}") { resolve(it, slot, resolution) }
    override fun keep(slot: Int) = local("keep slot ${slot + 1}") { keep(it, slot) }
    override fun restore(programId: String) = local("restore") { restore(programId) }
}

private sealed interface Route {
    data object List : Route
    data class Detail(val id: String) : Route
    data class Conflict(val slot: Int) : Route
}

@Composable
fun ProgramsScreen(source: ProgramsSource) {
    var route by remember { mutableStateOf<Route>(Route.List) }
    BackHandler(route != Route.List) { route = Route.List }
    val device by source.device.collectAsState()
    val live by source.live.collectAsState()
    val entries by remember(source) { source.entries() }.collectAsState(emptyList())
    val slots by remember(source, device) { device?.let(source::slots) ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(source) { source.errors.collect { snackbar.showSnackbar(it) } }
    Box(Modifier.fillMaxSize()) {
    when (val r = route) {
        Route.List -> ProgramsList(
            entries, slots, live,
            open = { route = Route.Detail(it) },
            review = { route = Route.Conflict(it) },
            act = { slot -> slotAction(source, slot) },
        )
        // A program deleted or a conflict resolved meanwhile has nothing left to show: back to the list.
        is Route.Detail -> entries.firstOrNull { it.id == r.id }?.let { e ->
            ProgramDetailScreen(source, e, slots, live, back = { route = Route.List })
        } ?: LaunchedEffect(r) { route = Route.List }
        is Route.Conflict -> slots.getOrNull(r.slot)?.takeIf { it.status is SlotStatus.ChangedOutside }?.let { v ->
            ConflictScreen(source, v, back = { route = Route.List })
        } ?: LaunchedEffect(r) { route = Route.List }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun slotAction(source: ProgramsSource, view: SlotView) {
    val slot = view.state.slot
    when (val s = view.status) {
        is SlotStatus.Older -> source.push(slot, s.entry.id)
        is SlotStatus.Interrupted -> source.push(slot, s.target.programId)
        is SlotStatus.Deleted -> source.restore(s.entry.id)
        SlotStatus.NotInLibrary -> source.keep(slot)
        else -> Unit
    }
}

/** What a slot row says, in which colour, and the one action it offers. */
data class SlotLine(val text: String, val color: Color, val action: String? = null, val ok: Boolean = false)

private val Warm = Color(0xFFF0A070)
val Muted = Color(0xFF9AA2AB)
private val Synced = Color(0xFF9AB0A8)

fun slotLine(status: SlotStatus): SlotLine = when (status) {
    SlotStatus.Unknown -> SlotLine("Not read yet", Muted)
    SlotStatus.Empty -> SlotLine("Empty", Muted)
    is SlotStatus.InSync -> SlotLine("In sync", Synced, ok = true)
    is SlotStatus.Older -> SlotLine("Older version · r${status.slotRev} here, r${status.entry.current.rev} in library", Amber, "Update")
    is SlotStatus.ChangedOutside -> SlotLine("Changed outside the app", Warm, "Review")
    is SlotStatus.Deleted -> SlotLine("Deleted from library", Muted, "Restore")
    SlotStatus.NotInLibrary -> SlotLine("Not in your library", Warm, "Keep")
    is SlotStatus.Interrupted -> SlotLine("Write interrupted", Warm, "Retry")
}

/** The unit stores names upper-case (the official app's keyboard); "TWOSTEP" reads better as "Twostep". */
fun prettyName(name: String) = if (name == name.uppercase()) name.lowercase().replaceFirstChar { it.uppercase() } else name

fun slotName(v: SlotView): String = v.state.program?.name?.let(::prettyName) ?: when (v.status) {
    SlotStatus.Unknown -> "—"
    else -> "Empty slot"
}

fun hhmm(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

/** "r6 · 6 steps · until 06:30", or with a total duration when no step ends at a clock time. */
fun programMeta(rev: Int?, steps: List<Step>, start: LocalTime): String {
    val spans = timeline(steps, start)
    val end = spans.lastOrNull()?.to ?: 0
    return listOfNotNull(
        rev?.takeIf { it > 1 }?.let { "r$it" },
        "${steps.size} step${if (steps.size == 1) "" else "s"}",
        if (steps.any { it.clock }) "until ${clockAt(start, end)}" else duration(end),
    ).joinToString(" · ")
}

@Composable
fun SectionTitle(text: String, right: String? = null) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    Text(text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Muted, modifier = Modifier.weight(1f))
    right?.let { Text(it, fontSize = 12.sp, color = Muted) }
}

@Composable
fun SlotBadge(n: Int) = Box(
    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
) { Text("$n", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }

@Composable
fun Pill(text: String, fg: Color, border: Color) = Text(
    text, fontSize = 11.sp, color = fg,
    modifier = Modifier.border(1.dp, border, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
)

@Composable
private fun Check() = Canvas(Modifier.size(20.dp)) {
    val u = size.width / 24f
    drawPath(Path().apply { moveTo(5 * u, 12 * u); lineTo(10 * u, 17 * u); lineTo(19 * u, 8 * u) }, Synced, style = Stroke(2 * u))
}

@Composable
fun ProgramsList(
    entries: List<LibraryEntry>,
    slots: List<SlotView>,
    live: Boolean,
    open: (String) -> Unit,
    review: (Int) -> Unit,
    act: (SlotView) -> Unit,
    start: LocalTime = LocalTime.now(),
) {
    val library = entries.filter { it.deletedAt == null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Programs", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Text("Your library, and what the BedJet holds.", fontSize = 14.sp, color = Muted)
            }
            // "+ New" waits for the program editor (ROADMAP); hidden rather than a button that does nothing.
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val read = slots.mapNotNull { it.state.readAt }.minOrNull()
                SectionTitle("On the BedJet", listOfNotNull(read?.let { "read ${hhmm(it)}" }, if (live) "live" else "offline").joinToString(" · "))
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    if (slots.isEmpty()) Text("No BedJet connected yet.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    slots.forEachIndexed { i, v ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        SlotRow(v, live, open, review, act)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Library", "${library.size} program${if (library.size == 1) "" else "s"}")
                if (library.isEmpty()) Text("Programs you keep from the BedJet show up here.", color = Muted, fontSize = 14.sp)
                library.forEach { e -> LibraryRow(e, slots, start) { open(e.id) } }
            }
        }
    }
}

@Composable
private fun SlotRow(v: SlotView, live: Boolean, open: (String) -> Unit, review: (Int) -> Unit, act: (SlotView) -> Unit) {
    val line = slotLine(v.status)
    val target = when (val s = v.status) {
        is SlotStatus.InSync -> { { open(s.entry.id) } }
        is SlotStatus.Older -> { { open(s.entry.id) } }
        is SlotStatus.Deleted -> null
        is SlotStatus.ChangedOutside -> { { review(v.state.slot) } }
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).let { if (target != null) it.clickable(onClick = target) else it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SlotBadge(v.state.slot + 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(slotName(v), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(line.text, fontSize = 12.sp, color = line.color)
        }
        when {
            line.ok -> Check()
            line.action == "Review" -> Text(line.action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            line.action != null -> {
                // Restore and Keep only touch the library; Update and Retry write to the unit and need the link.
                val needsUnit = line.action == "Update" || line.action == "Retry"
                val enabled = live || !needsUnit
                Text(
                    line.action, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else Muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled) { act(v) }.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(e: LibraryEntry, slots: List<SlotView>, start: LocalTime, onClick: () -> Unit) {
    val p = e.current.program
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(prettyName(p.name), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(programMeta(e.current.rev, p.steps, start), fontSize = 12.sp, color = Muted)
        }
        StepStrip(timeline(p.steps, start), height = 24.dp)
        val badges = slots.mapNotNull { v ->
            when (val s = v.status) {
                is SlotStatus.InSync -> "Slot ${v.state.slot + 1}".takeIf { s.entry.id == e.id }
                is SlotStatus.Older -> "Slot ${v.state.slot + 1} has r${s.slotRev}".takeIf { s.entry.id == e.id }
                else -> null
            }
        }
        if (badges.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            badges.forEach { Pill(it, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.outline) }
        }
    }
}

/**
 * Canned library for previews, and for the emulator while [ProgramLibrary] isn't wired: start the app with
 * `--ez fake_library true` in a debug build. Actions only log.
 */
class FakePrograms : ProgramsSource {
    override val device = MutableStateFlow<String?>("demo")
    override val live = MutableStateFlow(true)
    override val errors = MutableSharedFlow<String>()
    override val started = MutableStateFlow<StartedSlot?>(null)
    private val day = 86_400_000L
    private val t0 = System.currentTimeMillis()

    private fun s(mode: Mode, fan: Int, c: Double, min: Int, clock: Boolean = false) =
        Step(mode, (fan / 5) - 1, (c * 2).toInt(), min / 60, min % 60, clock)

    private val summer = listOf(
        s(Mode.TURBO, 100, 43.0, 4), s(Mode.WAIT, 5, 40.0, 10), s(Mode.COOL, 40, 20.0, 30),
        s(Mode.COOL, 30, 20.0, 240), s(Mode.COOL, 25, 20.0, 5 * 60 + 30, clock = true), s(Mode.EXT_HEAT, 35, 31.0, 6 * 60 + 30, clock = true),
    )
    private fun rev(id: String, rev: Int, p: Program, ago: Long, origin: Origin = Origin.EDIT, note: String? = null) =
        ProgramRevision(id, rev, p, t0 - ago, origin, note)

    val revs = mapOf(
        "summer" to listOf(
            rev("summer", 6, Program("Summer nights", summer), day * 2, note = "Last cool step until 05:30"),
            rev("summer", 5, Program("Summer nights", summer), day * 3, note = "Fan 35 → 30 % in step 4"),
            rev("summer", 4, Program("Summer nights", summer.drop(1)), day * 4, note = "Wait step added"),
            rev("summer", 3, Program("Summer nights", summer.drop(2)), day * 5, Origin.OUTSIDE),
        ),
        "neutral" to listOf(rev("neutral", 1, Program("Neutral 15–25", listOf(s(Mode.TURBO, 100, 43.0, 5), s(Mode.TURBO, 100, 43.0, 4), s(Mode.WAIT, 5, 40.0, 10), s(Mode.COOL, 40, 20.0, 20), s(Mode.COOL, 25, 20.0, 390))), day, Origin.TEMPLATE)),
        "hot" to listOf(rev("hot", 1, Program("Hot 26+", listOf(s(Mode.TURBO, 100, 43.0, 3), s(Mode.TURBO, 100, 43.0, 3), s(Mode.WAIT, 5, 40.0, 10), s(Mode.COOL, 55, 20.0, 20), s(Mode.COOL, 30, 20.0, 360))), day, Origin.TEMPLATE)),
        "cold" to listOf(rev("cold", 1, Program("Cold, sleep + wake", listOf(s(Mode.TURBO, 100, 43.0, 5), s(Mode.TURBO, 100, 43.0, 5), s(Mode.WAIT, 5, 40.0, 10), s(Mode.COOL, 30, 20.0, 10), s(Mode.HEAT, 30, 23.5, 390), s(Mode.HEAT, 40, 35.0, 10), s(Mode.HEAT, 50, 38.0, 10), s(Mode.HEAT, 60, 40.0, 10))), day, Origin.TEMPLATE)),
        "sleep" to listOf(
            rev("sleep", 3, Program("SLEEEEP", listOf(s(Mode.TURBO, 100, 43.0, 5), s(Mode.WAIT, 5, 40.0, 10), s(Mode.COOL, 55, 20.0, 20), s(Mode.COOL, 25, 20.0, 390), s(Mode.HEAT, 35, 35.0, 10))), day, Origin.OUTSIDE),
            rev("sleep", 2, Program("SLEEEEP", listOf(s(Mode.TURBO, 100, 43.0, 5), s(Mode.WAIT, 5, 40.0, 10), s(Mode.COOL, 40, 20.0, 20), s(Mode.COOL, 25, 20.0, 390))), day * 5, Origin.KEPT_FROM_SLOT),
        ),
    )
    private val entryList = listOf("summer", "neutral", "hot", "cold").map { LibraryEntry(it, revs.getValue(it).first()) } +
        LibraryEntry("sleep", revs.getValue("sleep")[1])
    private fun e(id: String) = entryList.first { it.id == id }

    private val slotList = listOf(
        SlotView(SlotState("demo", 0, revs.getValue("summer")[2].program, t0 - 600_000, PushRecord("summer", 4, "")), SlotStatus.Older(e("summer"), 4)),
        SlotView(SlotState("demo", 1, e("neutral").current.program, t0 - 600_000, PushRecord("neutral", 1, "")), SlotStatus.InSync(e("neutral"))),
        SlotView(SlotState("demo", 2, revs.getValue("sleep")[0].program, t0 - 600_000, PushRecord("sleep", 2, "")), SlotStatus.ChangedOutside(e("sleep"), 3)),
    )

    override fun entries(): Flow<List<LibraryEntry>> = flowOf(entryList)
    override fun slots(device: String): Flow<List<SlotView>> = flowOf(slotList)
    override suspend fun revisions(programId: String) = revs[programId].orEmpty()
    override suspend fun rankSlots(device: String, programId: String) = when (programId) {
        "summer" -> listOf(0, 1, 2)
        else -> listOf(1, 0, 2)
    }
    var log = MutableStateFlow(listOf<String>())
    private fun note(s: String) = log.update { it + s }
    override fun run(slot: Int, programId: String) = note("run $programId in ${slot + 1}")
    override fun push(slot: Int, programId: String) = note("push $programId to ${slot + 1}")
    override fun resolve(slot: Int, resolution: Resolution) = note("resolve ${slot + 1} $resolution")
    override fun keep(slot: Int) = note("keep ${slot + 1}")
    override fun restore(programId: String) = note("restore $programId")
}

private fun <T> MutableStateFlow<T>.update(f: (T) -> T) { value = f(value) }

@Preview(widthDp = 390, heightDp = 844, backgroundColor = 0xFF07090C, showBackground = true)
@Composable
private fun ProgramsListPreview() = JetCommandTheme {
    val fake = remember { FakePrograms() }
    val entries by fake.entries().collectAsState(emptyList())
    val slots by fake.slots("demo").collectAsState(emptyList())
    ProgramsList(entries, slots, live = true, open = {}, review = {}, act = {}, start = LocalTime.of(22, 30))
}

