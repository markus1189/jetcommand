package link.thesymbolic.jetcommand

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import link.thesymbolic.jetcommand.protocol.DeviceId
import link.thesymbolic.jetcommand.protocol.Fingerprint
import link.thesymbolic.jetcommand.protocol.LibraryEntry
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Origin
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.ProgramId
import link.thesymbolic.jetcommand.protocol.ProgramRevision
import link.thesymbolic.jetcommand.protocol.PushRecord
import link.thesymbolic.jetcommand.protocol.Resolution
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.SlotState
import link.thesymbolic.jetcommand.protocol.SlotStatus
import link.thesymbolic.jetcommand.protocol.SlotView
import link.thesymbolic.jetcommand.protocol.StartedSlot
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing

/**
 * Canned library for previews and the emulator: start the app with `--ez fake_library true` in a
 * debug build. Actions only log.
 */
class FakePrograms : ProgramsSource {
    override val device = MutableStateFlow<DeviceId?>(DeviceId("demo"))
    override val live = MutableStateFlow(true)
    override val errors = MutableSharedFlow<String>()
    override val started = MutableStateFlow<StartedSlot?>(null)
    private val day = 86_400_000L
    private val t0 = System.currentTimeMillis()

    private fun s(mode: Mode, fan: Int, c: Double, timing: Timing) =
        Step(mode, (fan / 5) - 1, (c * 2).toInt(), timing)

    private fun s(mode: Mode, fan: Int, c: Double, min: Int) =
        s(mode, fan, c, Timing.For(min / 60, min % 60))

    private val summer =
        listOf(
            s(Mode.TURBO, 100, 43.0, 4),
            s(Mode.WAIT, 5, 40.0, 10),
            s(Mode.COOL, 40, 20.0, 30),
            s(Mode.COOL, 30, 20.0, 240),
            s(Mode.COOL, 25, 20.0, Timing.Until(5, 30)),
            s(Mode.EXT_HEAT, 35, 31.0, Timing.Until(6, 30)),
        )

    private fun rev(
        id: String,
        rev: Int,
        p: Program,
        ago: Long,
        origin: Origin = Origin.EDIT,
        note: String? = null,
    ) = ProgramRevision(ProgramId(id), rev, p, t0 - ago, origin, note)

    val revs =
        mapOf(
            "summer" to
                listOf(
                    rev(
                        "summer",
                        6,
                        Program("Summer nights", summer),
                        day * 2,
                        note = "Last cool step until 05:30",
                    ),
                    rev(
                        "summer",
                        5,
                        Program("Summer nights", summer),
                        day * 3,
                        note = "Fan 35 → 30 % in step 4",
                    ),
                    rev(
                        "summer",
                        4,
                        Program("Summer nights", summer.drop(1)),
                        day * 4,
                        note = "Wait step added",
                    ),
                    rev(
                        "summer",
                        3,
                        Program("Summer nights", summer.drop(2)),
                        day * 5,
                        Origin.OUTSIDE,
                    ),
                ),
            "neutral" to
                listOf(
                    rev(
                        "neutral",
                        1,
                        Program(
                            "Neutral 15–25",
                            listOf(
                                s(Mode.TURBO, 100, 43.0, 5),
                                s(Mode.TURBO, 100, 43.0, 4),
                                s(Mode.WAIT, 5, 40.0, 10),
                                s(Mode.COOL, 40, 20.0, 20),
                                s(Mode.COOL, 25, 20.0, 390),
                            ),
                        ),
                        day,
                        Origin.TEMPLATE,
                    )
                ),
            "hot" to
                listOf(
                    rev(
                        "hot",
                        1,
                        Program(
                            "Hot 26+",
                            listOf(
                                s(Mode.TURBO, 100, 43.0, 3),
                                s(Mode.TURBO, 100, 43.0, 3),
                                s(Mode.WAIT, 5, 40.0, 10),
                                s(Mode.COOL, 55, 20.0, 20),
                                s(Mode.COOL, 30, 20.0, 360),
                            ),
                        ),
                        day,
                        Origin.TEMPLATE,
                    )
                ),
            "cold" to
                listOf(
                    rev(
                        "cold",
                        1,
                        Program(
                            "Cold, sleep + wake",
                            listOf(
                                s(Mode.TURBO, 100, 43.0, 5),
                                s(Mode.TURBO, 100, 43.0, 5),
                                s(Mode.WAIT, 5, 40.0, 10),
                                s(Mode.COOL, 30, 20.0, 10),
                                s(Mode.HEAT, 30, 23.5, 390),
                                s(Mode.HEAT, 40, 35.0, 10),
                                s(Mode.HEAT, 50, 38.0, 10),
                                s(Mode.HEAT, 60, 40.0, 10),
                            ),
                        ),
                        day,
                        Origin.TEMPLATE,
                    )
                ),
            "sleep" to
                listOf(
                    rev(
                        "sleep",
                        3,
                        Program(
                            "SLEEEEP",
                            listOf(
                                s(Mode.TURBO, 100, 43.0, 5),
                                s(Mode.WAIT, 5, 40.0, 10),
                                s(Mode.COOL, 55, 20.0, 20),
                                s(Mode.COOL, 25, 20.0, 390),
                                s(Mode.HEAT, 35, 35.0, 10),
                            ),
                        ),
                        day,
                        Origin.OUTSIDE,
                    ),
                    rev(
                        "sleep",
                        2,
                        Program(
                            "SLEEEEP",
                            listOf(
                                s(Mode.TURBO, 100, 43.0, 5),
                                s(Mode.WAIT, 5, 40.0, 10),
                                s(Mode.COOL, 40, 20.0, 20),
                                s(Mode.COOL, 25, 20.0, 390),
                            ),
                        ),
                        day * 5,
                        Origin.KEPT_FROM_SLOT,
                    ),
                ),
        )
    private val entryList =
        listOf("summer", "neutral", "hot", "cold").map {
            LibraryEntry(ProgramId(it), revs.getValue(it).first())
        } + LibraryEntry(ProgramId("sleep"), revs.getValue("sleep")[1])

    private fun e(id: String) = entryList.first { it.id.value == id }

    private val slotList =
        listOf(
            SlotView(
                SlotState(
                    DeviceId("demo"),
                    Slot.all[0],
                    revs.getValue("summer")[2].program,
                    t0 - 600_000,
                    PushRecord(ProgramId("summer"), 4, Fingerprint("")),
                ),
                SlotStatus.Older(e("summer"), 4),
            ),
            SlotView(
                SlotState(
                    DeviceId("demo"),
                    Slot.all[1],
                    e("neutral").current.program,
                    t0 - 600_000,
                    PushRecord(ProgramId("neutral"), 1, Fingerprint("")),
                ),
                SlotStatus.InSync(e("neutral")),
            ),
            SlotView(
                SlotState(
                    DeviceId("demo"),
                    Slot.all[2],
                    revs.getValue("sleep")[0].program,
                    t0 - 600_000,
                    PushRecord(ProgramId("sleep"), 2, Fingerprint("")),
                ),
                SlotStatus.ChangedOutside(e("sleep"), 3, revs.getValue("sleep")[0].program),
            ),
        )

    override fun entries(): Flow<List<LibraryEntry>> = flowOf(entryList)

    override fun slots(device: DeviceId): Flow<List<SlotView>> = flowOf(slotList)

    override suspend fun revisions(programId: ProgramId) = revs[programId.value].orEmpty()

    val log = MutableStateFlow(listOf<String>())

    private fun note(s: String) = log.update { it + s }

    override fun run(slot: Slot, programId: ProgramId) = note("run $programId in ${slot.number}")

    override fun push(slot: Slot, programId: ProgramId) = note("push $programId to ${slot.number}")

    override fun resolve(slot: Slot, resolution: Resolution) =
        note("resolve ${slot.number} $resolution")

    override fun keep(slot: Slot) = note("keep ${slot.number}")

    override fun restore(programId: ProgramId) = note("restore $programId")

    override fun delete(programId: ProgramId) = note("delete $programId")
}
