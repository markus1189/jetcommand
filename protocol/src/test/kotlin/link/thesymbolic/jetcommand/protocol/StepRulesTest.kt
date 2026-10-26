package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepRulesTest {
    private fun lasting(mode: Mode, temp: Int, fan: Int, minutes: Int) =
        Step(mode, fan, temp, Timing.For(minutes / 60, minutes % 60))

    private fun until(mode: Mode, temp: Int, hour: Int, minute: Int, fan: Int = 9) =
        Step(mode, fan, temp, Timing.Until(hour, minute))

    private fun waitUntil(hour: Int, minute: Int) = until(Mode.WAIT, 80, hour, minute, fan = 19)

    private fun heat(temp: Int, fan: Int, minutes: Int = 60) =
        lasting(Mode.HEAT, temp, fan, minutes)

    private fun cool(temp: Int, minutes: Int = 60) = lasting(Mode.COOL, temp, 9, minutes)

    private val wait = lasting(Mode.WAIT, 80, 19, 60)

    /** Edits [step] standing first in a program started at 22:00. */
    private fun edit(step: Step, edit: StepEdit) =
        applyEdit(step, edit, planProgram(listOf(step), 22 * 60).single())

    @Test
    fun `heat durations follow the runtime table`() {
        assertEquals(1..60, durationRange(heat(80, 19)))
        assertEquals(1..120, durationRange(heat(75, 19)))
        assertEquals(1..240, durationRange(heat(80, 0)))
        assertEquals(1..60, durationRange(heat(81, 0)))
        assertEquals(1..720, durationRange(heat(66, 19)))
    }

    @Test
    fun `turbo runs at most 10 minutes, other modes 12 h`() {
        assertEquals(1..10, durationRange(lasting(Mode.TURBO, 86, 19, 5)))
        assertEquals(1..720, durationRange(cool(38)))
        assertEquals(1..720, durationRange(wait))
    }

    @Test
    fun `a clock-time step may end 0 to 12 h after the previous end`() {
        fun refused(step: Step, start: Int) = planProgram(listOf(step), start).single().refused
        assertFalse(refused(waitUntil(10, 0), 600))
        assertEquals(0, planProgram(listOf(waitUntil(10, 0)), 600).single().wanted)
        assertFalse(refused(waitUntil(22, 0), 600))
        assertTrue(refused(waitUntil(22, 1), 600))
    }

    @Test
    fun `the clock-time gap counts across midnight from the previous planned end`() {
        val twenty = lasting(Mode.WAIT, 80, 19, 20)
        fun plan(hour: Int, minute: Int) =
            planProgram(listOf(twenty, waitUntil(hour, minute)), 1430)
        assertEquals(0, plan(0, 10)[1].wanted)
        assertEquals(PlannedStep(1450, 1450, 720, 720, refused = false), plan(12, 10)[1])
        assertTrue(plan(12, 11)[1].refused)
        assertEquals(1439, plan(0, 9)[1].wanted)
    }

    @Test
    fun `a cut step moves later steps but not their plan or a clock-time length`() {
        val plan = planProgram(listOf(heat(80, 19, 120), cool(44, 30), waitUntil(6, 30)), 1320)
        assertEquals(PlannedStep(1320, 1320, 120, 60, refused = false), plan[0])
        assertTrue(plan[0].cut)
        assertEquals(PlannedStep(1440, 1380, 30, 30, refused = false), plan[1])
        assertEquals(PlannedStep(1470, 1410, 360, 360, refused = false), plan[2])
    }

    @Test
    fun `a program without clock-time steps starts any time`() {
        assertEquals(listOf(ClockWindow(0, 1439)), startWindows(listOf(heat(80, 19, 600), wait)))
    }

    @Test
    fun `summer nights starts from 12_46 to 0_46`() {
        val steps =
            listOf(
                lasting(Mode.TURBO, 86, 19, 4),
                lasting(Mode.WAIT, 80, 19, 10),
                cool(44, 30),
                cool(44, 240),
                until(Mode.COOL, 44, 5, 30),
                until(Mode.EXT_HEAT, 62, 6, 30),
            )
        // 284 min of steps come before 05:30, which must then lie 0 to 12 h ahead.
        assertEquals(listOf(ClockWindow(766, 46)), startWindows(steps))
    }

    @Test
    fun `clock-time steps more than 12 h apart never start`() {
        assertEquals(
            emptyList(),
            startWindows(listOf(waitUntil(1, 0), until(Mode.COOL, 44, 14, 0))),
        )
    }

    @Test
    fun `a mode switch from one without a temperature lands on its start temperature`() {
        assertEquals(Edited(heat(64, 19)), edit(wait, StepEdit.SetMode(Mode.HEAT)))
    }

    @Test
    fun `a mode switch clamps the temperature into the new range`() {
        assertEquals(Edited(heat(62, 9)), edit(cool(62), StepEdit.SetMode(Mode.HEAT)))
        assertEquals(Edited(heat(45, 9)), edit(cool(38), StepEdit.SetMode(Mode.HEAT)))
        assertEquals(Edited(cool(62)), edit(heat(80, 9), StepEdit.SetMode(Mode.COOL)))
    }

    @Test
    fun `a duration over the cap is shortened`() {
        assertEquals(
            Edited(heat(80, 19, 60), EditNote.Shortened(60)),
            edit(heat(80, 19), StepEdit.SetDuration(90)),
        )
    }

    @Test
    fun `raising temperature or fan shortens a step over the new cap`() {
        assertEquals(
            Edited(heat(80, 19, 60), EditNote.Shortened(60)),
            edit(heat(75, 19, 120), StepEdit.SetTemp(80)),
        )
        assertEquals(
            Edited(heat(80, 19, 60), EditNote.Shortened(60)),
            edit(heat(80, 0, 240), StepEdit.SetFan(19)),
        )
    }

    @Test
    fun `a mode without clock time takes the planned length`() {
        // From 22:00 until 04:00.
        assertEquals(
            Edited(heat(45, 9, 360), EditNote.ClockTimeDropped(Mode.HEAT, 360)),
            edit(until(Mode.COOL, 38, 4, 0), StepEdit.SetMode(Mode.HEAT)),
        )
    }

    @Test
    fun `to clock time ends where the step ends by plan`() {
        assertEquals(Edited(heat(70, 9, 240)), edit(heat(70, 9, 240), StepEdit.ToUntil))
        assertEquals(
            Edited(until(Mode.COOL, 44, 2, 0)),
            edit(cool(44, 240), StepEdit.ToUntil),
        )
    }

    @Test
    fun `to duration keeps the planned length`() {
        assertEquals(Edited(cool(44, 390)), edit(until(Mode.COOL, 44, 4, 30), StepEdit.ToDuration))
    }

    @Test
    fun `fan and temperature edits clamp to their ranges`() {
        assertEquals(19, edit(cool(44), StepEdit.SetFan(25)).step.fan)
        assertEquals(0, edit(cool(44), StepEdit.SetFan(-1)).step.fan)
        assertEquals(62, edit(cool(44), StepEdit.SetTemp(90)).step.temp)
        assertEquals(45, edit(heat(60, 9), StepEdit.SetTemp(10)).step.temp)
        assertEquals(wait, edit(wait, StepEdit.SetTemp(50)).step)
    }
}
