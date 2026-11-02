package link.thesymbolic.jetcommand.protocol

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlanTest {
    private val ready =
        CommandSnapshot(
            connected = true,
            demo = false,
            link = LinkState.Ready,
            remembered = true,
            hasConnectPermission = true,
        )
    private val away = ready.copy(connected = false, link = LinkState.Disconnected())

    // Marks a run that waits for Ready; how long is [readyTimeoutMs]'s.
    private val waits = 0L

    @Test
    fun `a shortcut waits out a timed-out connect, the longest backoff and a retry`() =
        assertEquals(50_000, readyTimeoutMs(BedJetLink.Config(reconnectMaxMs = 10_000)))

    private val fan = UserCommand.SetFan(FanStep.of(9)!!)
    private val press = UserCommand.Press(Key.FAN_UP)

    private fun run(command: UserCommand, connect: Boolean, timeout: Long?, report: Boolean): Plan {
        assertEquals(timeout != null, report, "only waiting runs report")
        return if (timeout == null) {
            assertEquals(false, connect, "only waiting runs connect")
            Plan.Run.Now(command, describe(command))
        } else {
            Plan.Run.WhenReady(command, describe(command), connect)
        }
    }

    private fun rejected(command: UserCommand, reason: String, report: Boolean) =
        Plan.Rejected(describe(command), reason, report)

    private fun rejectedRange(request: CommandRequest, reason: String, report: Boolean): Plan {
        assertIs<CommandRequest.OutOfRange>(request)
        assertEquals(reason, request.reason)
        return Plan.Rejected(request.label, reason, report)
    }

    @Test
    fun `app and notification fire at once while connected, failures go to the log`() {
        for (origin in listOf(CommandOrigin.APP, CommandOrigin.NOTIFICATION)) {
            assertEquals(run(fan, false, null, false), plan(fan, origin, ready), "$origin")
            assertEquals(run(press, false, null, false), plan(press, origin, ready), "$origin")
            val connecting = ready.copy(link = LinkState.Connecting(2))
            assertEquals(run(fan, false, null, false), plan(fan, origin, connecting), "$origin")
        }
    }

    @Test
    fun `app and notification are rejected quietly when not connected`() {
        for (origin in listOf(CommandOrigin.APP, CommandOrigin.NOTIFICATION)) assertEquals(
            rejected(fan, "Not connected", false),
            plan(fan, origin, away),
            "$origin",
        )
    }

    @Test
    fun `a shortcut on a connected unit waits for Ready and reports`() {
        assertEquals(
            run(fan, false, waits, true),
            plan(fan, CommandOrigin.SHORTCUT, ready),
        )
    }

    @Test
    fun `a shortcut reconnects to the remembered unit`() {
        assertEquals(
            run(fan, true, waits, true),
            plan(fan, CommandOrigin.SHORTCUT, away),
        )
    }

    @Test
    fun `a shortcut switches from the demo to the remembered unit`() {
        val demo = ready.copy(demo = true)
        assertEquals(
            run(fan, true, waits, true),
            plan(fan, CommandOrigin.SHORTCUT, demo),
        )
        assertEquals(
            rejected(fan, "Connect to your BedJet in the app once first.", true),
            plan(fan, CommandOrigin.SHORTCUT, demo.copy(remembered = false)),
        )
    }

    @Test
    fun `the app and the notification still drive the demo`() {
        val demo = ready.copy(demo = true)
        for (origin in listOf(CommandOrigin.APP, CommandOrigin.NOTIFICATION)) assertEquals(
            run(fan, false, null, false),
            plan(fan, origin, demo),
            "$origin",
        )
    }

    @Test
    fun `a shortcut never presses, connected or not`() {
        for (snapshot in listOf(ready, away, away.copy(remembered = false))) {
            val plan = plan(press, CommandOrigin.SHORTCUT, snapshot)
            assertIs<Plan.Rejected>(plan, "$snapshot")
            assertTrue(plan.report)
        }
    }

    @Test
    fun `a shortcut without a remembered unit sends them to the app to connect once`() {
        assertEquals(
            rejected(fan, "Connect to your BedJet in the app once first.", true),
            plan(fan, CommandOrigin.SHORTCUT, away.copy(remembered = false)),
        )
    }

    @Test
    fun `a shortcut without the connect permission sends them to the app`() {
        assertEquals(
            rejected(fan, "Open JetCommand to allow Bluetooth.", true),
            plan(fan, CommandOrigin.SHORTCUT, away.copy(hasConnectPermission = false)),
        )
    }

    @Test
    fun `fan steps 0 to 19 pass, one beyond either end is rejected`() {
        for (origin in CommandOrigin.entries) {
            assertIs<Plan.Run>(plan(UserCommand.SetFan(FanStep.of(0)!!), origin, ready))
            assertIs<Plan.Run>(plan(UserCommand.SetFan(FanStep.of(19)!!), origin, ready))
            for (step in listOf(-1, 20)) {
                val request = CommandRequest.fan(step)
                assertEquals(
                    rejectedRange(
                        request,
                        "Fan must be 5–100 %.",
                        origin == CommandOrigin.SHORTCUT,
                    ),
                    plan(request, origin, ready),
                    "$origin $step",
                )
            }
        }
    }

    @Test
    fun `runtimes 1 min to 12 h pass, one minute beyond either end is rejected`() {
        for (origin in CommandOrigin.entries) {
            assertIs<Plan.Run>(plan(UserCommand.SetRuntime(AutoOff.of(1)!!), origin, ready))
            assertIs<Plan.Run>(plan(UserCommand.SetRuntime(AutoOff.of(720)!!), origin, ready))
            for (minutes in listOf(0, 721)) {
                val request = CommandRequest.runtime(minutes)
                assertEquals(
                    rejectedRange(
                        request,
                        "Auto off must be 1 min to 12 h.",
                        origin == CommandOrigin.SHORTCUT,
                    ),
                    plan(request, origin, ready),
                    "$origin $minutes",
                )
            }
        }
    }

    @Test
    fun `temperatures 19 to 43 C pass, one half degree beyond either end is rejected`() {
        for (origin in CommandOrigin.entries) {
            assertIs<Plan.Run>(plan(UserCommand.SetTemperature(TargetTemp.of(38)!!), origin, ready))
            assertIs<Plan.Run>(plan(UserCommand.SetTemperature(TargetTemp.of(86)!!), origin, ready))
            for (halfC in listOf(37, 87)) {
                val request = CommandRequest.temperature(halfC)
                assertEquals(
                    rejectedRange(
                        request,
                        "Temperature must be 19–43°C.",
                        origin == CommandOrigin.SHORTCUT,
                    ),
                    plan(request, origin, ready),
                    "$origin $halfC",
                )
            }
        }
    }

    @Test
    fun `an out-of-range value is rejected before the connection is looked at`() {
        val tooHot = CommandRequest.temperature(100)
        assertEquals(
            rejectedRange(tooHot, "Temperature must be 19–43°C.", false),
            plan(tooHot, CommandOrigin.APP, away),
        )
    }

    @Test
    fun `every plan is labelled with describe and only shortcuts report`() = runTest {
        val commands =
            listOf(
                UserCommand.SetMode(SettableMode.HEAT),
                UserCommand.SetTemperature(TargetTemp.of(42)!!),
                fan,
                UserCommand.StartProgram(Slot.all.last()),
                press,
            )
        checkAll(
            Arb.int(commands.indices),
            Arb.enum<CommandOrigin>(),
            Arb.boolean(),
            Arb.boolean(),
            Arb.boolean(),
        ) { i, origin, connected, remembered, permission ->
            val command = commands[i]
            val snapshot =
                ready.copy(
                    connected = connected,
                    remembered = remembered,
                    hasConnectPermission = permission,
                )
            val plan = plan(command, origin, snapshot)
            val (label, report) =
                when (plan) {
                    is Plan.Run -> plan.label to plan.report
                    is Plan.Rejected -> plan.label to plan.report
                }
            assertEquals(describe(command), label)
            assertEquals(origin == CommandOrigin.SHORTCUT, report, "$origin $plan")
        }
    }

    @Test
    fun `describe names each command`() {
        val expected =
            listOf(
                UserCommand.SetMode(SettableMode.STANDBY) to "off",
                UserCommand.SetMode(SettableMode.HEAT) to "mode Heat",
                UserCommand.SetMode(SettableMode.EXT_HEAT) to "mode Ext. heat",
                UserCommand.SetTemperature(TargetTemp.of(43)!!) to "temp 21.5°C",
                UserCommand.SetTemperature(TargetTemp.of(42)!!) to "temp 21°C",
                UserCommand.SetFan(FanStep.of(9)!!) to "fan 50%",
                UserCommand.StartProgram(Slot.of(1)!!) to "start program 2",
                UserCommand.Press(Key.FAN_UP) to "press fan up",
                UserCommand.Press(Key.TEMP_DOWN_C) to "press temp down",
                UserCommand.SetRuntime(AutoOff.of(65)!!) to "auto off in 1:05",
            )
        for ((command, name) in expected) assertEquals(name, describe(command))
    }

    @Test
    fun `an unconfirmed start counts as sent, a refused one as failed`() {
        assertEquals(
            RunOutcome.Unconfirmed("sent, not confirmed"),
            runOutcomeOf(StartOutcome.Failed(StartFailure.UNCONFIRMED)),
        )
        for (reason in listOf(StartFailure.CLOCK_NOT_SET, StartFailure.STEP_TOO_LONG)) assertEquals(
            RunOutcome.Failed(reason.text),
            runOutcomeOf(StartOutcome.Failed(reason)),
        )
    }
}
