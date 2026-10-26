package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/** Program start and run as the firmware does them (`FUN_400ece98`, `FUN_400e65bc`). */
class BedJetSimTest {
    private fun started(vararg steps: Step, clock: Int? = 22 * 3600) =
        BedJetSim(slots = listOf(Program("p", steps.toList()), null, null), clock = clock).also {
            it.send(Command.startProgram(checkNotNull(Slot.of(0))))
        }

    private fun lasting(mode: Mode, temp: Int, fan: Int, minutes: Int) =
        Step(mode, fan, temp, Timing.For(minutes / 60, minutes % 60))

    private fun until(hour: Int, minute: Int) = Step(Mode.WAIT, 19, 80, Timing.Until(hour, minute))

    @Test
    fun `a program without clock-time steps starts with the clock unset`() {
        val sim = started(lasting(Mode.COOL, 44, 9, 30), clock = null)
        assertEquals(1, sim.step)
        assertEquals(Mode.COOL, sim.mode)
        assertEquals(Notification.None, sim.notification)
    }

    @Test
    fun `a clock-time step needs the clock`() {
        val sim = started(lasting(Mode.COOL, 44, 9, 30), until(6, 30), clock = null)
        assertEquals(0, sim.step)
        assertEquals(Notification.ProgramNeedsClock, sim.notification)
    }

    @Test
    fun `a clock-time step may end exactly 12 h out, not a minute later`() {
        val ok = started(until(10, 0))
        assertEquals(1, ok.step)
        assertEquals(720 * 60, ok.remainingSeconds)
        val late = started(until(10, 1))
        assertEquals(0, late.step)
        assertEquals(Notification.ProgramTooLong, late.notification)
    }

    @Test
    fun `a clock-time step at the previous step's end runs no time`() {
        val sim =
            started(lasting(Mode.COOL, 44, 9, 30), until(22, 30), lasting(Mode.COOL, 44, 9, 10))
        sim.skipAhead(30.0)
        assertEquals(3, sim.step)
        assertEquals(10 * 60, sim.remainingSeconds)
    }

    @Test
    fun `a long duration step starts and is cut to the runtime cap`() {
        val sim = started(lasting(Mode.HEAT, 80, 19, 180), lasting(Mode.COOL, 44, 9, 30))
        assertEquals(1, sim.step)
        assertEquals(60 * 60, sim.remainingSeconds)
        sim.skipAhead(60.0)
        assertEquals(2, sim.step)
    }

    @Test
    fun `a clock-time step after a cut step keeps its planned length`() {
        // Planned 22:00 to 00:00, but the heat cap ends it at 23:00; 06:30 was fixed from 00:00.
        val sim = started(lasting(Mode.HEAT, 80, 19, 120), until(6, 30))
        sim.skipAhead(60.0)
        assertEquals(2, sim.step)
        assertEquals(390 * 60, sim.remainingSeconds)
        sim.skipAhead(390.0)
        assertEquals(0, sim.step)
        assertEquals(5 * 3600 + 30 * 60, sim.clock)
    }
}
