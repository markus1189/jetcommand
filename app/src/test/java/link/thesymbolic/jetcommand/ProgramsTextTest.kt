package link.thesymbolic.jetcommand

import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.PushRecord
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.SlotState
import link.thesymbolic.jetcommand.protocol.SlotStatus
import link.thesymbolic.jetcommand.protocol.SlotView
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing

class ProgramsTextTest {
    private fun cool(fan: Int, min: Int) = Step(Mode.COOL, fan, 40, Timing.For(min / 60, min % 60))

    private val p = Program("SUMMER", listOf(cool(5, 90), cool(3, 30)))
    private val other = Program("Other", listOf(cool(9, 60)))

    private fun rev(id: String, n: Int, prog: Program = p, origin: Origin = Origin.EDIT) =
        ProgramRevision(id, n, prog, 0, origin)

    private val summer = LibraryEntry("summer", rev("summer", 3))
    private val neutral = LibraryEntry("neutral", rev("neutral", 1, other))

    private fun view(i: Int, status: SlotStatus, prog: Program? = p) =
        SlotView(SlotState("d", Slot.all[i], prog, readAt = 1, PushRecord("x", 1, "")), status)

    private val older = SlotStatus.Older(summer, 2)
    private val outside = SlotStatus.ChangedOutside(summer, 4, other)
    private val statuses =
        listOf(
            SlotStatus.Unknown,
            SlotStatus.Empty,
            SlotStatus.InSync(summer),
            older,
            outside,
            SlotStatus.Deleted(summer),
            SlotStatus.NotInLibrary(other),
            SlotStatus.Interrupted(PushRecord("summer", 3, "")),
        )

    @Test
    fun `plural counts one apart`() {
        assertEquals(listOf("0 steps", "1 step", "2 steps"), (0..2).map { plural(it, "step") })
    }

    @Test
    fun `slot line per status, with only Update and Retry needing the unit`() {
        val lines = statuses.map(::slotLine)
        assertEquals(
            listOf(
                "Not read yet",
                "Empty",
                "In sync",
                "Older version · r2 here, r3 in library",
                "Changed outside the app",
                "Deleted from library",
                "Not in your library",
                "Write interrupted",
            ),
            lines.map { it.text },
        )
        assertEquals(
            listOf(null, null, null, "Update", "Review", "Restore", "Keep", "Retry"),
            lines.map { it.action?.label },
        )
        assertEquals(
            listOf(SlotAction.UPDATE, SlotAction.RETRY),
            lines.mapNotNull { it.action }.filter { it.needsUnit },
        )
        assertEquals(listOf(true), lines.filter { it.ok }.map { it.tone == SlotTone.SYNCED })
    }

    @Test
    fun `tapping opens linked programs and reviews outside changes, nothing else`() {
        assertEquals(
            listOf(
                null,
                null,
                SlotTap.Open("summer"),
                SlotTap.Open("summer"),
                SlotTap.Review(Slot.all[0]),
                null,
                null,
                null,
            ),
            statuses.map { slotTap(view(0, it)) },
        )
    }

    @Test
    fun `names read well and slots say what they hold`() {
        assertEquals("Summer", prettyName("SUMMER"))
        assertEquals("Hot 26+", prettyName("Hot 26+"))
        assertEquals("—", slotName(view(0, SlotStatus.Unknown, null)))
        assertEquals("Empty slot", slotName(view(0, SlotStatus.Empty, null)))
        assertEquals("Summer", slotName(view(0, SlotStatus.InSync(summer))))
        assertEquals("Summer r2", optionTitle(view(0, older)))
        assertEquals("Summer", optionTitle(view(0, SlotStatus.InSync(summer))))
    }

    @Test
    fun `hhmm formats in the given zone`() {
        assertEquals("01:02", hhmm(3_720_000, ZoneOffset.UTC))
    }

    @Test
    fun `program meta shows rev above 1, steps and the end`() {
        val start = LocalTime.of(22, 0)
        assertEquals("r3 · 2 steps · 2 h 00", programMeta(3, p.steps, start))
        assertEquals("1 step · 1 h 00", programMeta(1, other.steps, start))
        val clock = listOf(cool(5, 30), Step(Mode.COOL, 5, 40, Timing.Until(6, 30)))
        assertEquals("2 steps · until 06:30", programMeta(null, clock, start))
    }

    @Test
    fun `library badges name the slots holding the entry`() {
        val slots =
            listOf(
                view(0, SlotStatus.InSync(summer)),
                view(1, older),
                view(2, SlotStatus.InSync(neutral)),
            )
        assertEquals(listOf("Slot 1", "Slot 2 has r2"), libraryBadges("summer", slots))
        assertEquals(listOf("Slot 3"), libraryBadges("neutral", slots))
        assertEquals(emptyList(), libraryBadges("nope", slots))
    }

    @Test
    fun `revision pill prefers current, then the slot holding it, then outside`() {
        val slots = listOf(view(0, older))
        assertEquals(RevisionPill.Current, revisionPill(rev("summer", 3), summer, slots))
        assertEquals(
            RevisionPill.OnSlot(Slot.all[0]),
            revisionPill(rev("summer", 2, origin = Origin.OUTSIDE), summer, slots),
        )
        assertEquals(
            RevisionPill.Outside,
            revisionPill(rev("summer", 1, origin = Origin.OUTSIDE), summer, slots),
        )
        assertNull(revisionPill(rev("summer", 1), summer, slots))
        assertNull(
            revisionPill(rev("summer", 2), summer, listOf(view(0, SlotStatus.Older(neutral, 2))))
        )
    }

    @Test
    fun `pick notes warn exactly where a write saves or loses something`() {
        val notes = statuses.map { pickNote(view(0, it), summer) }
        assertEquals(
            listOf(false, false, false, false, true, false, true, true),
            notes.map { it.warn },
        )
        assertEquals("Already loaded · runs as it is", notes[2].text)
        assertEquals("Older version · r2 stays in history", notes[3].text)
        assertEquals(
            "In sync · stays in your library",
            pickNote(view(0, SlotStatus.InSync(summer)), neutral).text,
        )
        assertEquals(
            "Older version · stays in your library",
            pickNote(view(0, older), neutral).text,
        )
    }

    @Test
    fun `picker text follows where the program is and what is picked`() {
        val holding = listOf(view(0, SlotStatus.InSync(summer)), view(1, SlotStatus.Empty, null))
        assertEquals(
            PickerText(
                "Run Summer",
                "It's on the BedJet already.",
                "Run from slot 1",
                "Sets the clock, then starts.",
            ),
            pickerText(summer, holding, Slot.all[0], run = true),
        )
        val run2 = pickerText(summer, holding, Slot.all[1], run = true)
        assertEquals("Load into slot 2 and run", run2.button)
        assertEquals("Writes, reads back to check, sets the clock, then starts.", run2.caption)
        val load =
            pickerText(
                summer,
                listOf(view(0, SlotStatus.Empty, null), view(1, older)),
                Slot.all[0],
                run = false,
            )
        assertEquals("Load Summer", load.title)
        assertEquals("Slot 2 holds an older version. Which slot gets this one?", load.sub)
        assertEquals("Load into slot 1", load.button)
        assertEquals("Writes, then reads back to check.", load.caption)
        assertEquals(
            "It isn't on the BedJet yet. Which slot does it replace?",
            pickerText(summer, emptyList(), Slot.all[0], run = false).sub,
        )
    }

    @Test
    fun `step cells and list details`() {
        assertEquals("" to "—", stepCell(null))
        assertEquals("Cool" to "fan 30 % · 20°C · 1 h 30", stepCell(cool(5, 90)))
        val wait = Step(Mode.WAIT, 0, 0, Timing.Until(6, 5))
        assertEquals("until 06:05", stepCell(wait).second)
        assertEquals("fan off · no temperature", stepListDetail(wait))
    }

    @Test
    fun `step diff pads the shorter side and counts changed rows`() {
        val rows = stepDiff(p.steps, listOf(cool(5, 90)))
        assertEquals(listOf(false, true), rows.map { it.changed })
        assertNull(rows[1].theirs)
        assertEquals("1 step differ · same name", diffSummary(rows, "SUMMER", "SUMMER"))
        assertEquals(
            "0 steps differ · renamed to Winter",
            diffSummary(stepDiff(p.steps, p.steps), "SUMMER", "WINTER"),
        )
    }

    @Test
    fun `origin texts are distinct`() {
        assertEquals(Origin.entries.size, Origin.entries.map(::originText).toSet().size)
    }
}
