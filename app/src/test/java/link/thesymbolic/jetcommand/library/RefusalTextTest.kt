package link.thesymbolic.jetcommand.library

import kotlin.test.Test
import kotlin.test.assertEquals
import link.thesymbolic.jetcommand.protocol.ErrorCode
import link.thesymbolic.jetcommand.protocol.Refusal

class RefusalTextTest {
    // The texts the exceptions carried before refusals became values.
    @Test
    fun `every refusal reads as before`() {
        assertEquals(
            mapOf(
                Refusal.PROGRAM_RUNNING to
                    "Stop the program first: a running program's slot can't be written.",
                Refusal.NOT_IN_LIBRARY to "This program is not in the library.",
                Refusal.DELETED to "This program was deleted; restore it first.",
                Refusal.SLOT_CHANGED to ErrorCode.SLOT_CHANGED.text,
                Refusal.STALE to "Couldn't keep slot 2.",
                Refusal.CLOCK_NOT_SET to ErrorCode.CLOCK_NOT_SET.text,
                Refusal.STEP_TOO_LONG to ErrorCode.STEP_TOO_LONG.text,
                Refusal.START_UNCONFIRMED to "Your BedJet didn't confirm that the program started.",
            ),
            Refusal.entries.associateWith { refusalText(it, "keep slot 2") },
        )
    }
}
