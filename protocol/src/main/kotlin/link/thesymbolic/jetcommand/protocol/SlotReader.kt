package link.thesymbolic.jetcommand.protocol

/** Reads program slots off a unit; the library's sync path needs nothing else of the device. */
interface SlotReader {
    suspend fun programNames(): List<String?>

    suspend fun programSteps(slot: Slot): List<Step>

    /** One entry per [Slot.all], null for an empty slot. */
    suspend fun storedPrograms(): List<Program?>
}
