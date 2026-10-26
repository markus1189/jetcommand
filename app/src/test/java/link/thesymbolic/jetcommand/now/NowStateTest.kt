package link.thesymbolic.jetcommand.now

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.hoursMinutes
import link.thesymbolic.jetcommand.protocol.LinkState
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Notification
import link.thesymbolic.jetcommand.protocol.Program
import link.thesymbolic.jetcommand.protocol.SettableMode
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.SlotState
import link.thesymbolic.jetcommand.protocol.SlotStatus
import link.thesymbolic.jetcommand.protocol.SlotView
import link.thesymbolic.jetcommand.protocol.StartedSlot
import link.thesymbolic.jetcommand.protocol.StatusUpdate
import link.thesymbolic.jetcommand.protocol.Step
import link.thesymbolic.jetcommand.protocol.Timing
import link.thesymbolic.jetcommand.protocol.maxRuntimeMinutes
import link.thesymbolic.jetcommand.status

private const val DEV = "AA:BB"

private fun steps(n: Int, minutes: Int = 30) =
    List(n) { Step(Mode.HEAT, 9, 70, Timing.For(minutes / 60, minutes % 60)) }

private fun slot(index: Int, program: Program?, device: String = DEV) =
    SlotView(
        SlotState(device, Slot.all[index], program, readAt = 1L),
        program?.let(SlotStatus::NotInLibrary) ?: SlotStatus.Empty,
    )

private val NOON = LocalTime.NOON

private val base =
    NowInputs(
        stackLabel = "Demo BedJet",
        link = LinkState.Ready,
        update = StatusUpdate(status(), extraFresh = true),
        statusAgeSeconds = 2,
        remembered = null,
        canConnect = true,
        programNames = emptyList(),
        device = DEV,
        started = null,
        slots = emptyList(),
    )

class NowStateTest {
    private val two = Program("TWOSTEP", steps(2))
    private val five = Program("Five", steps(5))

    @Test
    fun `running program choice`() {
        val started0 = StartedSlot(DEV, Slot.all[0])
        // name, step, started, slots, expected
        listOf(
                Row("no step", 0, null, listOf(slot(0, five)), null),
                Row("only fit", 3, null, listOf(slot(0, two), slot(1, five)), five),
                Row("two fit", 2, null, listOf(slot(0, two), slot(1, five)), null),
                Row("started wins", 2, started0, listOf(slot(0, two), slot(1, five)), two),
                Row("started too short", 3, started0, listOf(slot(0, two), slot(1, five)), five),
                Row(
                    "started elsewhere",
                    2,
                    StartedSlot("other", Slot.all[0]),
                    listOf(slot(0, two), slot(1, five)),
                    null,
                ),
                Row("started slot empty", 2, started0, listOf(slot(0, null), slot(1, five)), five),
                Row("other device's cache", 2, null, listOf(slot(0, five, device = "x")), null),
                Row("nothing cached", 1, started0, emptyList(), null),
            )
            .forEach { r ->
                assertEquals(r.expected, runningProgram(r.step, DEV, r.started, r.slots), r.name)
            }
    }

    private data class Row(
        val name: String,
        val step: Int,
        val started: StartedSlot?,
        val slots: List<SlotView>,
        val expected: Program?,
    )

    @Test
    fun `running program comes from this device's slots and covers the step`() = runTest {
        checkAll(
            Arb.int(-1..8),
            Arb.element(DEV, "other").orNull(),
            startedArb,
            Arb.list(slotArb, 0..3),
        ) { step, device, started, slots ->
            val p = runningProgram(step, device, started, slots) ?: return@checkAll
            assertTrue(
                slots.any { it.state.device == device && it.state.program == p },
                "$p not in cache",
            )
            assertTrue(p.steps.size >= step && step > 0)
        }
    }

    @Test
    fun `body follows link and stack`() {
        val notReady = base.copy(link = LinkState.Connecting(2))
        listOf(
                base.copy(stackLabel = null, link = LinkState.Disconnected()) to
                    NowBody.Offline(null),
                base.copy(
                    stackLabel = null,
                    link = LinkState.Disconnected(),
                    remembered = "BedJet 3",
                ) to NowBody.Offline("BedJet 3"),
                base.copy(
                    stackLabel = null,
                    link = LinkState.Disconnected(),
                    remembered = "BedJet 3",
                    canConnect = false,
                ) to NowBody.Offline(null),
                notReady to NowBody.Waiting,
                base.copy(update = null) to NowBody.Waiting,
            )
            .forEach { (inputs, body) ->
                assertEquals(body, nowState(inputs, NOON).body, "$inputs")
            }
    }

    @Test
    fun `header shows the age only with a live status`() {
        listOf(
                base to "Demo BedJet · Ready · 2 s ago",
                base.copy(link = LinkState.Connecting(2)) to "Demo BedJet · Reconnecting (2)",
                base.copy(stackLabel = null, link = LinkState.Disconnected(true)) to "Gave up",
            )
            .forEach { (inputs, header) -> assertEquals(header, nowState(inputs, NOON).header) }
        assertEquals(false, nowState(base.copy(stackLabel = null), NOON).canDisconnect)
        assertEquals(true, nowState(base.copy(link = LinkState.Connecting()), NOON).canDisconnect)
    }

    @Test
    fun `live summary names an unknown program step`() {
        fun summary(inputs: NowInputs) = (nowState(inputs, NOON).body as NowBody.Live).summary
        val heat = StatusUpdate(status(remainingSeconds = 3_900, programStep = 0), true)
        val inProgram = StatusUpdate(status(remainingSeconds = 600, programStep = 2), true)
        val off = StatusUpdate(status(mode = Mode.STANDBY, programStep = 2), true)
        listOf(
                // The countdown card shows a manual mode's time.
                base.copy(update = heat) to "",
                base.copy(update = inProgram) to "auto off 0:10 · program step 2",
                base.copy(update = inProgram, slots = listOf(slot(0, two))) to "",
                base.copy(update = off) to "program step 2",
            )
            .forEach { (inputs, text) -> assertEquals(text, summary(inputs)) }
    }

    @Test
    fun `live state carries the running view and the program buttons`() {
        val inputs =
            base.copy(
                update = StatusUpdate(status(remainingSeconds = 600, programStep = 1), true),
                slots = listOf(slot(0, two)),
                programNames = listOf("TWOSTEP", null, "Warm up"),
            )
        val live = nowState(inputs, NOON).body as NowBody.Live
        assertEquals("Twostep · step 1/2", live.running?.title)
        assertEquals(
            listOf(ProgramButton(Slot.all[0], "Twostep"), ProgramButton(Slot.all[2], "Warm up")),
            live.programs,
        )
    }

    @Test
    fun `program buttons skip empty slots and ignore names past the last slot`() {
        listOf(
                emptyList<String?>() to emptyList(),
                listOf(null, null, null) to emptyList(),
                listOf("A", "B", "C", "D") to
                    listOf(
                        ProgramButton(Slot.all[0], "A"),
                        ProgramButton(Slot.all[1], "B"),
                        ProgramButton(Slot.all[2], "C"),
                    ),
            )
            .forEach { (names, buttons) -> assertEquals(buttons, programButtons(names)) }
    }

    @Test
    fun `running view places now inside the current step`() {
        val p = Program("Mixed", steps(3, minutes = 60))
        // 22:00, step 2 of three one-hour steps with 15 min left: started 20:15, now is minute 105.
        val v = runningView(p, 2, 15 * 60, LocalTime.of(22, 0))!!
        assertEquals("Mixed · step 2/3", v.title)
        assertEquals("00:15:00", v.left)
        assertEquals("then heat 1 h 00", v.next)
        assertEquals(105.0, v.nowMinute)
        assertEquals(listOf(0 to 60, 60 to 120, 120 to 180), v.spans.map { it.from to it.to })
        assertEquals("last step", runningView(p, 3, 75 * 60, LocalTime.of(22, 0))!!.next)
        assertNull(runningView(Program("Empty", emptyList()), 1, 60, NOON))
    }

    @Test
    fun `running view of a clock-time step uses the inferred start`() {
        val p =
            Program(
                "Night",
                listOf(
                    Step(Mode.HEAT, 9, 70, Timing.For(1, 0)),
                    Step(Mode.COOL, 9, 40, Timing.Until(6, 0)),
                ),
            )
        // 23:30 in step 1 with 30 min left: started 23:00, step 2 runs 00:00 → 06:00.
        val v = runningView(p, 1, 30 * 60, LocalTime.of(23, 30))!!
        assertEquals(listOf(0 to 60, 60 to 420), v.spans.map { it.from to it.to })
        assertEquals("then cool 6 h 00", v.next)
        assertEquals("until 06:00 · last step", runningView(p, 2, 3_600, LocalTime.of(5, 0))!!.next)
    }

    @Test
    fun `temperature field`() {
        val heat = status(mode = Mode.HEAT, targetTemp = 70, actualTemp = 61)
        // Heat's fallback range is 45..80.
        assertEquals(
            FieldView("35°C", "actual 30.5°C", 25, 36, 45, enabled = true, hollow = false),
            tempField(heat, preview = null, sent = null),
        )
        assertEquals(
            FieldView("36°C", "sending · now 35°C", 27, 36, 45, enabled = true, hollow = true),
            tempField(heat, preview = null, sent = 72),
        )
        assertEquals("37°C", tempField(heat, preview = 74, sent = 72).text)
        assertEquals(false, tempField(heat, preview = null, sent = 70).hollow)
        val turbo = tempField(status(mode = Mode.TURBO, targetTemp = 86), null, null)
        assertEquals(
            listOf("43°C", "fixed in Turbo", "false"),
            turbo.let {
                listOf(it.text, it.sub, "${it.enabled}")
            },
        )
        assertEquals("--", tempField(status(mode = Mode.STANDBY), null, null).text)
        assertEquals(50, tempField(heat, null, null).valueAt(5))
    }

    @Test
    fun `fan field`() {
        val cool = status(mode = Mode.COOL, fanStep = 9)
        assertEquals(
            FieldView("50%", "", 9, 20, 0, enabled = true, hollow = false),
            fanField(cool, null, null),
        )
        assertEquals(
            FieldView("100%", "sending · now 50%", 19, 20, 0, enabled = true, hollow = true),
            fanField(cool, null, 19),
        )
        listOf(Mode.STANDBY, Mode.WAIT).forEach { m ->
            val f = fanField(status(mode = m), null, null)
            assertEquals("--" to false, f.text to f.enabled, "$m")
        }
    }

    @Test
    fun `small helpers`() {
        assertEquals("Ext", shortLabel(Mode.EXT_HEAT))
        assertEquals("Cool", shortLabel(Mode.COOL))
        assertEquals(SettableMode.HEAT, undoTarget(Mode.HEAT))
        assertNull(undoTarget(Mode.WAIT))
        listOf(0 to "0:00", 59 to "0:00", 3_599 to "0:59", 3_600 to "1:00", 36_000 to "10:00")
            .forEach { (s, text) -> assertEquals(text, hoursMinutes(s)) }
    }

    @Test
    fun `state derivation never throws`() = runTest {
        checkAll(inputsArb, Arb.int(0..1439)) { inputs, minute ->
            val state = nowState(inputs, LocalTime.of(minute / 60, minute % 60))
            assertTrue(state.header.isNotEmpty())
        }
    }

    @Test
    fun `actual holds through half-degree dither`() {
        val shown = listOf(61, 62, 61, 62, 63, 62, 63, 60).runningFold(null as Int?, ::steadyActual)
        assertEquals(listOf(null, 61, 61, 61, 61, 63, 63, 63, 60), shown)
        assertEquals("30.0°C", formatActual(60))
        assertEquals("actual 31.0°C", tempField(status(mode = Mode.HEAT), null, null, 62).sub)
    }

    @Test
    fun `fields never throw`() = runTest {
        checkAll(statusArb, Arb.int(-5..300).orNull(), Arb.int(-5..300).orNull()) { st, a, b ->
            tempField(st, a, b)
            fanField(st, a, b)
        }
    }

    private val ten = LocalTime.of(22, 0)

    private fun labels(v: CountdownView?) = v!!.nudges.map { it.label }

    @Test
    fun `countdown nudges step by 1, 5 and 15 minutes inside the limits`() {
        val v = countdownView(status(Mode.HEAT, remainingSeconds = 167 * 60), null, ten)!!
        assertEquals(listOf("−15", "−5", "−1", "+1", "+5", "+15"), labels(v))
        assertEquals(listOf(152, 162, 166, 168, 172, 182), v.nudges.map { it.target })
        assertEquals("2:47", v.left)
        assertEquals("off at 00:47", v.offAt)
        assertEquals("max 12:00", v.ceiling)
    }

    @Test
    fun `a step past a limit goes to the limit, and none at the limit`() {
        val turbo = status(Mode.TURBO, remainingSeconds = 7 * 60)
        val v = countdownView(turbo, null, ten)!!
        assertEquals(listOf("to 0:01", "−5", "−1", "+1", "to 0:10", "to 0:10"), labels(v))
        val atMax = countdownView(turbo, 10, ten)!!
        assertEquals(listOf(null, null, null), atMax.nudges.takeLast(3).map { it.target })
        assertEquals(listOf("+1", "+5", "+15"), labels(atMax).takeLast(3))
        val atMin = countdownView(turbo, 1, ten)!!
        assertEquals(listOf(null, null, null), atMin.nudges.take(3).map { it.target })
    }

    @Test
    fun `the shown time is the minute the nudges count from`() {
        val turbo = status(Mode.TURBO, remainingSeconds = 9 * 60 + 50)
        assertEquals("0:10", countdownView(turbo, null, ten)!!.left)
    }

    @Test
    fun `taps build on the draft, not the status`() {
        val v = countdownView(status(Mode.COOL, remainingSeconds = 3_600), 75, ten)!!
        assertEquals("1:15", v.left)
        assertEquals(76, v.nudges[3].target)
    }

    @Test
    fun `no countdown card in standby or a program`() {
        assertNull(countdownView(status(Mode.STANDBY), null, ten))
        assertNull(countdownView(status(Mode.HEAT, programStep = 2), null, ten))
        assertNull(countdownView(status(Mode.WAIT, programStep = 1), null, ten))
    }

    @Test
    fun `the ceiling is the official app's, whatever the status says`() {
        val hot = status(Mode.HEAT, targetTemp = 80, fanStep = 19).copy(maxRuntimeMinutes = 600)
        assertEquals("max 1:00", countdownView(hot, null, ten)!!.ceiling)
        assertEquals("max 12:00", countdownView(status(Mode.COOL), null, ten)!!.ceiling)
    }

    @Test
    fun `targets always stay within 1 min and the ceiling`() = runTest {
        val modes = listOf(Mode.HEAT, Mode.TURBO, Mode.EXT_HEAT, Mode.COOL, Mode.DRY)
        checkAll(
            Arb.element(modes),
            Arb.int(0..120),
            Arb.int(0..19),
            Arb.int(0..43_200),
            Arb.int(1..720).orNull(),
        ) { mode, temp, fan, secs, draft ->
            val st = status(mode, remainingSeconds = secs, targetTemp = temp, fanStep = fan)
            val ceiling = maxRuntimeMinutes(mode, temp, fan)
            countdownView(st, draft, ten)!!
                .nudges
                .mapNotNull { it.target }
                .forEach {
                    assertTrue(it in 1..ceiling, "$mode $temp $fan $secs $draft -> $it")
                }
        }
    }
}

private val stepArb = arbitrary {
    val timing =
        if (Arb.boolean().bind()) Timing.For(Arb.int(0..12).bind(), Arb.int(0..59).bind())
        else Timing.Until(Arb.int(0..23).bind(), Arb.int(0..59).bind())
    Step(Arb.enum<Mode>().bind(), Arb.int(0..19).bind(), Arb.int(0..120).bind(), timing)
}

private val programArb = arbitrary {
    Program(Arb.string(0..8).bind(), Arb.list(stepArb, 0..6).bind())
}

private val slotArb = arbitrary {
    slot(
        Arb.int(0..2).bind(),
        programArb.orNull().bind(),
        device = Arb.element(DEV, "other").bind(),
    )
}

private val startedArb = arbitrary {
    StartedSlot(Arb.element(DEV, "other").bind(), Slot.all[Arb.int(0..2).bind()])
}
    .orNull()

private val statusArb = arbitrary {
    status(
        mode = Arb.enum<Mode>().bind(),
        remainingSeconds = Arb.int(-100..50_000).bind(),
        actualTemp = Arb.int(0..120).bind(),
        targetTemp = Arb.int(0..120).bind(),
        fanStep = Arb.int(0..19).bind(),
        minTemp = Arb.int(0..120).bind(),
        maxTemp = Arb.int(0..120).bind(),
        programStep = Arb.int(-1..8).orNull().bind(),
        notification = Notification.of(Arb.int(0..5).bind()),
    )
}

private val linkArb =
    Arb.element(
        LinkState.Ready,
        LinkState.Connecting(),
        LinkState.Connecting(3),
        LinkState.Disconnected(),
        LinkState.Disconnected(gaveUp = true),
    )

private val inputsArb = arbitrary {
    NowInputs(
        stackLabel = Arb.string(0..10).orNull().bind(),
        link = linkArb.bind(),
        update = statusArb.orNull().bind()?.let { StatusUpdate(it, Arb.boolean().bind()) },
        statusAgeSeconds = Arb.long(0L..10_000L).bind(),
        remembered = Arb.string(0..10).orNull().bind(),
        canConnect = Arb.boolean().bind(),
        programNames = Arb.list(Arb.string(0..8).orNull(), 0..5).bind(),
        device = Arb.element(DEV, "other").orNull().bind(),
        started = startedArb.bind(),
        slots = Arb.list(slotArb, 0..3).bind(),
    )
}
