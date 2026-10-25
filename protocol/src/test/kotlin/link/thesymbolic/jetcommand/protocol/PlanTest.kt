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

    private val fan = UserCommand.SetFan(9)
    private val press = UserCommand.Press(Button.FAN_UP)

    private fun run(command: UserCommand, connect: Boolean, timeout: Long?, report: Boolean) =
        Plan.Run(command, describe(command), connect, timeout, report)

    private fun rejected(command: UserCommand, reason: String, report: Boolean) =
        Plan.Rejected(describe(command), reason, report)

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
            run(fan, false, READY_TIMEOUT_MS, true),
            plan(fan, CommandOrigin.SHORTCUT, ready),
        )
    }

    @Test
    fun `a shortcut reconnects to the remembered unit`() {
        assertEquals(
            run(fan, true, READY_TIMEOUT_MS, true),
            plan(fan, CommandOrigin.SHORTCUT, away),
        )
    }

    @Test
    fun `a shortcut switches from the demo to the remembered unit`() {
        val demo = ready.copy(demo = true)
        assertEquals(
            run(fan, true, READY_TIMEOUT_MS, true),
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
            assertIs<Plan.Run>(plan(UserCommand.SetFan(0), origin, ready))
            assertIs<Plan.Run>(plan(UserCommand.SetFan(19), origin, ready))
            for (step in listOf(-1, 20)) {
                val command = UserCommand.SetFan(step)
                assertEquals(
                    rejected(command, "Fan must be 5–100 %.", origin == CommandOrigin.SHORTCUT),
                    plan(command, origin, ready),
                    "$origin $step",
                )
            }
        }
    }

    @Test
    fun `temperatures 19 to 43 C pass, one half degree beyond either end is rejected`() {
        for (origin in CommandOrigin.entries) {
            assertIs<Plan.Run>(plan(UserCommand.SetTemperature(38), origin, ready))
            assertIs<Plan.Run>(plan(UserCommand.SetTemperature(86), origin, ready))
            for (halfC in listOf(37, 87)) {
                val command = UserCommand.SetTemperature(halfC)
                assertEquals(
                    rejected(
                        command,
                        "Temperature must be 19–43°C.",
                        origin == CommandOrigin.SHORTCUT,
                    ),
                    plan(command, origin, ready),
                    "$origin $halfC",
                )
            }
        }
    }

    @Test
    fun `a button the codec refuses to encode is rejected`() {
        val reset = UserCommand.Press(0x4c)
        assertEquals(
            rejected(reset, "Unknown button.", false),
            plan(reset, CommandOrigin.APP, ready),
        )
    }

    @Test
    fun `an out-of-range value is rejected before the connection is looked at`() {
        val tooHot = UserCommand.SetTemperature(100)
        assertEquals(
            rejected(tooHot, "Temperature must be 19–43°C.", false),
            plan(tooHot, CommandOrigin.APP, away),
        )
    }

    @Test
    fun `every plan is labelled with describe and only shortcuts report`() = runTest {
        val commands =
            listOf(
                UserCommand.SetMode(Mode.HEAT),
                UserCommand.SetTemperature(42),
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
                UserCommand.SetMode(Mode.STANDBY) to "off",
                UserCommand.SetMode(Mode.HEAT) to "mode Heat",
                UserCommand.SetMode(Mode.EXT_HEAT) to "mode Ext. heat",
                UserCommand.SetTemperature(43) to "temp 21.5°C",
                UserCommand.SetTemperature(42) to "temp 21°C",
                UserCommand.SetFan(9) to "fan 50%",
                UserCommand.StartProgram(Slot.of(1)!!) to "start program 2",
                UserCommand.Press(Button.FAN_UP) to "press fan up",
                UserCommand.Press(Button.TEMP_DOWN_C) to "press temp down",
                UserCommand.Press(0xee) to "press button 0xee",
            )
        for ((command, name) in expected) assertEquals(name, describe(command))
    }
}
