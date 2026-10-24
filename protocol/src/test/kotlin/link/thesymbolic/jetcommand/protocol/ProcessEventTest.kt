package link.thesymbolic.jetcommand.protocol

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import link.thesymbolic.jetcommand.protocol.ProcessEvent.Exit
import link.thesymbolic.jetcommand.protocol.ProcessEvent.Note
import link.thesymbolic.jetcommand.protocol.ProcessEvent.Started

class ProcessEventTest {
    /**
     * Written by the app before typed process events (e1d60a8), dumped from its own formatting code
     * for reason codes -1..18, so these are bytes that sit in users' night logs.
     */
    private val legacyExits =
        listOf(
            "exit reason -1 importance 100: null" to Exit(-1, 100, "null"),
            "exit reason 0 importance 125: (signal 9)" to Exit(0, 125, "(signal 9)"),
            "exit self importance 400: stop" to Exit(1, 400, "stop"),
            "exit signaled importance 1000: kill: a (b) (c)" to Exit(2, 1000, "kill: a (b) (c)"),
            "exit low memory importance 230: x importance 3: y" to
                Exit(3, 230, "x importance 3: y"),
            "exit crash importance 100: null" to Exit(4, 100, "null"),
            "exit native crash importance 125: (signal 9)" to Exit(5, 125, "(signal 9)"),
            "exit anr importance 400: stop" to Exit(6, 400, "stop"),
            "exit init failure importance 1000: kill: a (b) (c)" to
                Exit(7, 1000, "kill: a (b) (c)"),
            "exit permission change importance 230: x importance 3: y" to
                Exit(8, 230, "x importance 3: y"),
            "exit excessive resource usage importance 100: null" to Exit(9, 100, "null"),
            "exit user requested importance 125: (signal 9)" to Exit(10, 125, "(signal 9)"),
            "exit user stopped importance 400: stop" to Exit(11, 400, "stop"),
            "exit dependency died importance 1000: kill: a (b) (c)" to
                Exit(12, 1000, "kill: a (b) (c)"),
            "exit other importance 230: x importance 3: y" to Exit(13, 230, "x importance 3: y"),
            "exit freezer importance 100: null" to Exit(14, 100, "null"),
            "exit package state change importance 125: (signal 9)" to Exit(15, 125, "(signal 9)"),
            "exit package updated importance 400: stop" to Exit(16, 400, "stop"),
            "exit reason 17 importance 1000: kill: a (b) (c)" to Exit(17, 1000, "kill: a (b) (c)"),
            "exit reason 18 importance 230: x importance 3: y" to
                Exit(18, 230, "x importance 3: y"),
        )

    @Test
    fun `every exit the old code wrote parses to its reason and formats back byte for byte`() {
        legacyExits.forEach { (text, exit) ->
            assertEquals(exit, ProcessEvent.parse(text), text)
            assertEquals(text, exit.detail)
        }
    }

    @Test
    fun `system kills are what the old prefix list matched - signaled, low memory, excessive resource use`() {
        val oldKillPrefixes = listOf("signaled ", "low memory ", "excessive resource usage ")
        legacyExits.forEach { (text, _) ->
            val exit = ProcessEvent.parse(text) as Exit
            assertEquals(
                oldKillPrefixes.any { text.startsWith("exit $it") },
                exit.killedBySystem,
                text,
            )
        }
    }

    @Test
    fun `the other process lines the app writes`() {
        assertEquals(Started, ProcessEvent.parse("process started"))
        listOf("task removed", "service destroyed", "trim memory 15").forEach {
            assertEquals(Note(it), ProcessEvent.parse(it))
        }
    }

    @Test
    fun `unknown and malformed text stays a note, as written`() {
        listOf(
                "",
                "exit",
                "exit low memory",
                "exit something odd importance 1: x",
                "exit reason 2 importance 1: x", // 2 is "signaled": not what we would write
                "exit signaled importance 007: x",
                "exit signaled importance 99999999999: x",
                "Process started",
            )
            .forEach { assertEquals(Note(it), ProcessEvent.parse(it), it) }
    }

    @Test
    fun `parse inverts detail for every exit reason, importance and description`() = runTest {
        val code = Arb.choice(Arb.enum<ExitReason>().map { it.code }, Arb.int())
        checkAll(Arb.bind(code, Arb.int(), Arb.string(0..40), ::Exit)) { exit ->
            assertEquals(exit, ProcessEvent.parse(exit.detail))
        }
    }

    @Test
    fun `detail inverts parse for any text, so passing a line through never changes it`() =
        runTest {
            val piece =
                Arb.choice(
                    Arb.string(0..6),
                    Arb.element(
                        "exit ",
                        "process started",
                        " importance ",
                        ": ",
                        "reason ",
                        "-",
                        "007",
                        "12",
                        "\n",
                    ),
                    Arb.enum<ExitReason>().map { it.label },
                )
            checkAll(Arb.list(piece, 0..8).map { it.joinToString("") }) { text ->
                assertEquals(text, ProcessEvent.parse(text).detail)
            }
        }
}
