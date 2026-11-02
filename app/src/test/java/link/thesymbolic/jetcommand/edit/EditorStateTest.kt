package link.thesymbolic.jetcommand.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.thesymbolic.jetcommand.protocol.core.ClockWindow
import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.PlannedStep
import link.thesymbolic.jetcommand.protocol.core.Step
import link.thesymbolic.jetcommand.protocol.core.StepEdit
import link.thesymbolic.jetcommand.protocol.core.Timing
import link.thesymbolic.jetcommand.protocol.core.planProgram
import link.thesymbolic.jetcommand.protocol.core.startWindows

class EditorStateTest {
    private fun step(mode: Mode, fan: Int, temp: Int, min: Int) =
        Step(mode, fan, temp, Timing.For(min / 60, min % 60))

    private fun until(mode: Mode, h: Int, m: Int) = Step(mode, 4, 40, Timing.Until(h, m))

    private val summer =
        listOf(
            step(Mode.TURBO, 19, 86, 4),
            step(Mode.WAIT, 0, 80, 10),
            step(Mode.COOL, 7, 40, 30),
            step(Mode.COOL, 5, 40, 240),
            until(Mode.COOL, 5, 30),
            Step(Mode.EXT_HEAT, 6, 62, Timing.Until(6, 30)),
        )

    private val s = EditorState("SUMMER NIGHTS", 6, summer)

    @Test
    fun `changes count steps that differ by position, plus the length difference`() {
        assertEquals(0, s.changes)
        val edited = s.edit(3, StepEdit.SetFan(6)).edit(2, StepEdit.SetFan(9))
        assertEquals(2, edited.changes)
        assertEquals("Editing r6 · 2 changes", statusText(6, edited.changes))
        assertEquals("r6 · no changes", statusText(6, s.changes))
        // A swap changes both positions; adding a step adds one.
        assertEquals(2, s.move(0, 1).changes)
        assertEquals(1, s.add().changes)
        assertEquals(0, s.edit(3, StepEdit.SetFan(6)).discard().changes)
    }

    @Test
    fun `reorder keeps the selected step selected`() {
        val moved = s.open(1).move(0, 3)
        assertEquals(listOf(1, 2, 3, 0, 4, 5).map(summer::get), moved.steps)
        assertEquals(0, moved.selected)
        assertEquals(3, s.open(0).move(0, 3).selected)
        assertEquals(2, s.open(1).move(4, 1).selected)
        assertSame(s, s.move(0, 6))
        assertEquals(listOf(0, 4, 1, 2, 3), List(5) { movedIndex(it, 1, 4) })
    }

    @Test
    fun `a dragged row drops where its centre crosses the other rows' midpoints`() {
        val mids = listOf(30f, 90f, 150f, 210f)
        assertEquals(3, dropTarget(1, insertionSlot(mids, 215f)))
        assertEquals(1, dropTarget(1, insertionSlot(mids, 100f)))
        assertEquals(0, dropTarget(2, insertionSlot(mids, 10f)))
    }

    @Test
    fun `a program holds 32 steps and at least one`() {
        val full = EditorState("X", 1, List(32) { NEW_STEP })
        assertTrue(full.full)
        assertSame(full, full.add())
        assertSame(full, full.duplicate(3))
        assertEquals(32, EditorState("X", 1, List(31) { NEW_STEP }).add().steps.size)
        val one = EditorState("X", 1, listOf(NEW_STEP))
        assertSame(one, one.delete(0))
        assertEquals(31, s.copy(steps = List(31) { NEW_STEP }).add().selected)
        assertEquals(
            listOf(0, 0, 1).map(summer::get),
            s.copy(steps = summer.take(2)).duplicate(0).steps,
        )
    }

    @Test
    fun `heat at 40 C and fan 100 percent shortens a long step and says why`() {
        val heat = s.edit(3, StepEdit.SetMode(Mode.HEAT)).edit(3, StepEdit.SetFan(19))
        val hot = heat.edit(3, StepEdit.SetTemp(80))
        assertEquals(Timing.For(1, 0), hot.steps[3].timing)
        assertEquals(
            StepNote(3, "Shortened to 1 h 00: Heat at 40°C, fan 100 % runs at most that long."),
            hot.note,
        )
        assertEquals("Heat at this setting: at most 1 h 00", timeHint(hot.steps[3], hot.planned[3]))
        assertNull(longer(hot.steps[3]))
    }

    @Test
    fun `switching a clock-time step to a mode without one keeps its length`() {
        val p = PlannedStep(plannedFrom = 0, from = 0, wanted = 90, runs = 90, refused = false)
        assertEquals(
            "Dry has no clock time: kept as 1 h 30 instead of until 05:30.",
            noteText(
                link.thesymbolic.jetcommand.protocol.core.EditNote.ClockTimeDropped(Mode.DRY, 90),
                summer[4],
                step(Mode.DRY, 4, 50, p.wanted),
            ),
        )
    }

    @Test
    fun `time steps by 5 min, turbo by 1, never above what the unit loads`() {
        assertEquals(StepEdit.SetDuration(235), shorter(summer[3]))
        assertEquals(StepEdit.SetDuration(3), shorter(summer[0]))
        assertNull(shorter(step(Mode.COOL, 0, 40, 3)))
        assertEquals(StepEdit.SetDuration(720), longer(step(Mode.COOL, 0, 40, 715)))
        assertNull(longer(step(Mode.TURBO, 19, 86, 10)))
        assertEquals(StepEdit.SetUntil(325), shorter(summer[4]))
        assertNull(fanBy(summer[1], 1))
        assertNull(tempBy(summer[0], 1))
        assertEquals(StepEdit.SetTemp(39), tempBy(summer[3], -1))
        assertNull(tempBy(step(Mode.COOL, 0, 38, 5), -1))
    }

    @Test
    fun `row texts`() {
        assertEquals("heater and fan off", detailText(summer[1]))
        assertEquals("fan 100 % · 43°C fixed", detailText(summer[0]))
        assertEquals("fan 40 % · 20°C", detailText(summer[2]))
        assertEquals("4 h 00", lengthText(summer[3]))
        assertEquals("until 05:30", lengthText(summer[4]))
        assertEquals("Turbo runs 1–10 minutes, never until a clock time.", untilWhy(Mode.TURBO))
        assertEquals("Run until works with Wait, Ext. heat and Cool only.", untilWhy(Mode.HEAT))
        assertNull(untilWhy(Mode.COOL))
        assertEquals("ends 06:30", "ends ${clock(s.planned.last().to)}")
    }

    @Test
    fun `issues - refused, cut, and a clock time reached early`() {
        val noon = planProgram(summer, 12 * 60)
        assertEquals(
            Issue(
                "The BedJet refuses to start: until 05:30 is 12 h 46 after the previous step ends (16:44). The limit is 12 h.",
                refused = true,
            ),
            issue(summer[4], noon[4], last = false),
        )
        val imported = listOf(step(Mode.HEAT, 19, 80, 180), until(Mode.COOL, 4, 0))
        val p = planProgram(imported, 22 * 60)
        assertEquals(
            Issue(
                "The BedJet stops this step after 1 h 00 (heat limit at 40°C, fan 100 %). Every later step starts 2 h 00 early.",
                refused = false,
            ),
            issue(imported[0], p[0], last = false),
        )
        assertEquals(
            Issue(
                "Ends 02:00, not 04:00: an earlier step stops early, and clock times become durations when the program starts.",
                refused = false,
            ),
            issue(imported[1], p[1], last = true),
        )
        assertNull(issue(summer[3], s.planned[3], last = false))
        // Refused at noon: the ext. heat step after it would read as reached early, but never runs.
        assertEquals(
            listOf(null, null, null, null, true, null),
            issues(summer, noon).map { it?.refused },
        )
        assertTrue(issue(summer[5], noon[5], last = true) != null)
    }

    @Test
    fun `start window across midnight, refused outside it`() {
        val steps = listOf(step(Mode.COOL, 4, 40, 60), until(Mode.COOL, 6, 0))
        val windows = startWindows(steps)
        assertEquals(listOf(ClockWindow(17 * 60, 5 * 60)), windows)
        val inside = windowText(steps, windows, planProgram(steps, 22 * 60), 22 * 60)
        assertEquals("Can start between 17:00 and 05:00", inside.title)
        assertEquals(
            "Clock-time steps (until 06:00) must be at most 12 h after the step before them, or the BedJet refuses to start.",
            inside.sub,
        )
        val outside = windowText(steps, windows, planProgram(steps, 16 * 60 + 45), 16 * 60 + 45)
        assertEquals("Refused at 16:45 · can start between 17:00 and 05:00", outside.title)
        assertTrue(outside.refused)
        assertEquals(
            "Can start any time",
            windowText(summer.take(4), startWindows(summer.take(4)), s.planned, 0).title,
        )
        assertEquals(
            "Can't start at any time",
            windowText(steps, emptyList(), emptyList(), 0).title,
        )
    }

    @Test
    fun `leave text and the next revision`() {
        val edited = s.edit(3, StepEdit.SetFan(6))
        assertEquals(
            "1 change to Summer nights is not saved. Saving makes r7; the BedJet keeps the version it holds until you update its slot.",
            leaveText(edited, 7),
        )
        assertEquals("Save as r7", saveLabel(nextRev(6, emptyList())))
    }
}
