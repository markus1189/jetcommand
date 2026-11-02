package link.thesymbolic.jetcommand.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.core.DeviceId
import link.thesymbolic.jetcommand.protocol.core.Mode
import link.thesymbolic.jetcommand.protocol.core.Program
import link.thesymbolic.jetcommand.protocol.core.Slot
import link.thesymbolic.jetcommand.protocol.core.SlotStatus
import link.thesymbolic.jetcommand.protocol.core.Step
import link.thesymbolic.jetcommand.protocol.core.Timing
import link.thesymbolic.jetcommand.protocol.ports.SlotReader
import link.thesymbolic.jetcommand.protocol.shell.InMemoryLibraryStore
import link.thesymbolic.jetcommand.protocol.shell.ProgramLibrary

/** [ProgramLibrary.refresh] needs only a [SlotReader], so no link or sim. */
class RefreshTest {
    private class FakeReader(val programs: List<Program?>) : SlotReader {
        override suspend fun programNames() = programs.map { it?.name }

        override suspend fun programSteps(slot: Slot) = programs[slot.index]?.steps.orEmpty()

        override suspend fun storedPrograms() = programs
    }

    @Test
    fun `refresh links a slot holding a library program and leaves the rest`() = runTest {
        val program = Program("Alpha", listOf(Step(Mode.COOL, 5, 40, Timing.For(1, 0))))
        val other = Program("Other", listOf(Step(Mode.HEAT, 3, 70, Timing.For(2, 0))))
        val lib = ProgramLibrary(InMemoryLibraryStore(), now = { 1 })
        val entry = lib.create(program)
        val dev = DeviceId("d")

        lib.refresh(dev, FakeReader(listOf(null, program, other)))

        assertEquals(
            listOf(SlotStatus.Empty, SlotStatus.InSync(entry), SlotStatus.NotInLibrary(other)),
            lib.slots(dev).first().map { it.status },
        )
    }
}
