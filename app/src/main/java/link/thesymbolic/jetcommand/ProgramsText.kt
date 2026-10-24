// What the program screens say and offer, as pure functions of the library state.
package link.thesymbolic.jetcommand

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.SlotStatus
import link.thesymbolic.jetcommand.protocol.SlotView
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing
import link.thesymbolic.jetcommand.protocol.formatTemp

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

enum class SlotTone {
    MUTED,
    SYNCED,
    AMBER,
    WARM,
}

enum class SlotAction(val label: String, val needsUnit: Boolean) {
    UPDATE("Update", needsUnit = true),
    /** A label only: the whole row opens the conflict screen. */
    REVIEW("Review", needsUnit = false),
    RESTORE("Restore", needsUnit = false),
    KEEP("Keep", needsUnit = false),
    RETRY("Retry", needsUnit = true),
}

/** What a slot row says, in which tone, and the one action it offers. */
data class SlotLine(
    val text: String,
    val tone: SlotTone,
    val action: SlotAction? = null,
    val ok: Boolean = false,
)

fun slotLine(status: SlotStatus): SlotLine =
    when (status) {
        SlotStatus.Unknown -> SlotLine("Not read yet", SlotTone.MUTED)
        SlotStatus.Empty -> SlotLine("Empty", SlotTone.MUTED)
        is SlotStatus.InSync -> SlotLine("In sync", SlotTone.SYNCED, ok = true)
        is SlotStatus.Older ->
            SlotLine(
                "Older version · r${status.slotRev} here, r${status.entry.current.rev} in library",
                SlotTone.AMBER,
                SlotAction.UPDATE,
            )
        is SlotStatus.ChangedOutside ->
            SlotLine("Changed outside the app", SlotTone.WARM, SlotAction.REVIEW)
        is SlotStatus.Deleted ->
            SlotLine("Deleted from library", SlotTone.MUTED, SlotAction.RESTORE)
        is SlotStatus.NotInLibrary ->
            SlotLine("Not in your library", SlotTone.WARM, SlotAction.KEEP)
        is SlotStatus.Interrupted -> SlotLine("Write interrupted", SlotTone.WARM, SlotAction.RETRY)
    }

/** Where tapping a slot row leads. */
sealed interface SlotTap {
    data class Open(val programId: String) : SlotTap

    data class Review(val slot: Slot) : SlotTap
}

fun slotTap(v: SlotView): SlotTap? =
    when (val s = v.status) {
        is SlotStatus.InSync -> SlotTap.Open(s.entry.id)
        is SlotStatus.Older -> SlotTap.Open(s.entry.id)
        is SlotStatus.ChangedOutside -> SlotTap.Review(v.state.slot)
        else -> null
    }

/**
 * The unit stores names upper-case (the official app's keyboard); "TWOSTEP" reads better as
 * "Twostep".
 */
fun prettyName(name: String) =
    if (name == name.uppercase()) name.lowercase().replaceFirstChar { it.uppercase() } else name

fun slotName(v: SlotView): String =
    v.state.program?.name?.let(::prettyName)
        ?: when (v.status) {
            SlotStatus.Unknown -> "—"
            else -> "Empty slot"
        }

fun hhmm(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().let {
        "%02d:%02d".format(it.hour, it.minute)
    }

/** "r6 · 6 steps · until 06:30", or with a total duration when no step ends at a clock time. */
fun programMeta(rev: Int?, steps: List<Step>, start: LocalTime): String {
    val spans = timeline(steps, start)
    val end = spans.lastOrNull()?.to ?: 0
    return listOfNotNull(
            rev?.takeIf { it > 1 }?.let { "r$it" },
            plural(steps.size, "step"),
            if (steps.any { it.timing is Timing.Until }) "until ${clockAt(start, end)}"
            else duration(end),
        )
        .joinToString(" · ")
}

/** A library row's pills: the slots holding [entryId], with the revision when it's older. */
fun libraryBadges(entryId: String, slots: List<SlotView>): List<String> = slots.mapNotNull { v ->
    when (val s = v.status) {
        is SlotStatus.InSync -> "Slot ${v.state.slot.number}".takeIf { s.entry.id == entryId }
        is SlotStatus.Older ->
            "Slot ${v.state.slot.number} has r${s.slotRev}".takeIf { s.entry.id == entryId }
        else -> null
    }
}

fun originText(o: Origin) =
    when (o) {
        Origin.BLANK -> "Created"
        Origin.TEMPLATE -> "From a template"
        Origin.EDIT -> "Edited"
        Origin.KEPT_FROM_SLOT -> "Kept from the BedJet"
        Origin.OUTSIDE -> "Read from the BedJet: changed in another app"
    }

/** The one pill a history row shows, most important first. */
sealed interface RevisionPill {
    data object Current : RevisionPill

    data class OnSlot(val slot: Slot) : RevisionPill

    data object Outside : RevisionPill
}

fun revisionPill(r: ProgramRevision, entry: LibraryEntry, slots: List<SlotView>): RevisionPill? {
    if (r.rev == entry.current.rev) return RevisionPill.Current
    val onSlot = slots.firstOrNull { v ->
        val s = v.status
        s is SlotStatus.Older && s.entry.id == entry.id && s.slotRev == r.rev
    }
    return when {
        onSlot != null -> RevisionPill.OnSlot(onSlot.state.slot)
        r.origin == Origin.OUTSIDE -> RevisionPill.Outside
        else -> null
    }
}

/** The consequence of writing [entry] over [v], as the picker words it. */
data class PickNote(val text: String, val warn: Boolean = false)

fun pickNote(v: SlotView, entry: LibraryEntry): PickNote =
    when (val s = v.status) {
        is SlotStatus.InSync ->
            if (s.entry.id == entry.id) PickNote("Already loaded · runs as it is")
            else PickNote("In sync · stays in your library")
        is SlotStatus.Older ->
            if (s.entry.id == entry.id) PickNote("Older version · r${s.slotRev} stays in history")
            else PickNote("Older version · stays in your library")
        SlotStatus.Empty -> PickNote("Empty")
        SlotStatus.Unknown -> PickNote("Not read yet · read before writing")
        is SlotStatus.ChangedOutside ->
            PickNote("Changed outside the app · saved to your library first", warn = true)
        is SlotStatus.NotInLibrary ->
            PickNote("Not in your library · saved to your library first", warn = true)
        is SlotStatus.Deleted -> PickNote("Deleted from library · stays restorable")
        is SlotStatus.Interrupted -> PickNote("Write interrupted", warn = true)
    }

/** A picker option's title: the slot's program, with its revision when it's an older one. */
fun optionTitle(v: SlotView): String =
    slotName(v) + ((v.status as? SlotStatus.Older)?.slotRev?.let { " r$it" } ?: "")

data class PickerText(val title: String, val sub: String, val button: String, val caption: String)

fun pickerText(entry: LibraryEntry, slots: List<SlotView>, picked: Slot, run: Boolean): PickerText {
    fun holds(v: SlotView?) = (v?.status as? SlotStatus.InSync)?.entry?.id == entry.id
    val older = slots.firstOrNull { (it.status as? SlotStatus.Older)?.entry?.id == entry.id }
    val pickedHolds = holds(slots.getOrNull(picked.index))
    val n = picked.number
    return PickerText(
        title = "${if (run) "Run" else "Load"} ${prettyName(entry.current.program.name)}",
        sub =
            when {
                slots.any(::holds) -> "It's on the BedJet already."
                older != null ->
                    "Slot ${older.state.slot.number} holds an older version. Which slot gets this one?"
                else -> "It isn't on the BedJet yet. Which slot does it replace?"
            },
        button =
            when {
                run && pickedHolds -> "Run from slot $n"
                run -> "Load into slot $n and run"
                else -> "Load into slot $n"
            },
        caption =
            when {
                run && pickedHolds -> "Sets the clock, then starts."
                run -> "Writes, reads back to check, sets the clock, then starts."
                else -> "Writes, then reads back to check."
            },
    )
}

fun stepListDetail(s: Step): String =
    when (s.mode) {
        Mode.TURBO -> "${stepDetail(s)} · ${formatTemp(s.temp)} fixed"
        Mode.WAIT -> "${stepDetail(s)} · no temperature"
        else -> stepDetail(s)
    }

/** A diff cell's two lines: mode, then setting and timing; a dash where the side has no step. */
fun stepCell(s: Step?): Pair<String, String> =
    if (s == null) "" to "—"
    else
        s.mode.label to
            listOfNotNull(
                    stepDetail(s).takeIf { s.mode != Mode.WAIT },
                    when (val t = s.timing) {
                        is Timing.Until -> "until %02d:%02d".format(t.hour, t.minute)
                        is Timing.For -> duration(t.totalMinutes)
                    },
                )
                .joinToString(" · ")

data class DiffRow(val mine: Step?, val theirs: Step?) {
    val changed
        get() = mine != theirs
}

/** Step by step by position; the shorter side pads with nulls. */
fun stepDiff(mine: List<Step>, theirs: List<Step>): List<DiffRow> =
    List(maxOf(mine.size, theirs.size)) { DiffRow(mine.getOrNull(it), theirs.getOrNull(it)) }

fun diffSummary(rows: List<DiffRow>, mineName: String, theirsName: String): String {
    val differ = rows.count { it.changed }
    val name = if (theirsName == mineName) "same name" else "renamed to ${prettyName(theirsName)}"
    return "${plural(differ, "step")} differ · $name"
}
