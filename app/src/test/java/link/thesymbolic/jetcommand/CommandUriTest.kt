package link.thesymbolic.jetcommand

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import link.thesymbolic.jetcommand.protocol.core.AutoOff
import link.thesymbolic.jetcommand.protocol.core.CommandRequest
import link.thesymbolic.jetcommand.protocol.core.FanStep
import link.thesymbolic.jetcommand.protocol.core.Key
import link.thesymbolic.jetcommand.protocol.core.SettableMode
import link.thesymbolic.jetcommand.protocol.core.Slot
import link.thesymbolic.jetcommand.protocol.core.TargetTemp
import link.thesymbolic.jetcommand.protocol.core.UserCommand
import org.w3c.dom.Element

class CommandUriTest {
    @Test
    fun everyCommandRoundTrips() {
        val commands =
            SettableMode.entries.map(UserCommand::SetMode) +
                Key.entries.map(UserCommand::Press) +
                Slot.all.map(UserCommand::StartProgram) +
                listOf(
                    UserCommand.SetTemperature(TargetTemp.of(TargetTemp.RANGE.first)!!),
                    UserCommand.SetTemperature(TargetTemp.of(TargetTemp.RANGE.last)!!),
                    UserCommand.SetFan(FanStep.of(0)!!),
                    UserCommand.SetFan(FanStep.of(19)!!),
                    UserCommand.SetRuntime(AutoOff.of(1)!!),
                    UserCommand.SetRuntime(AutoOff.of(720)!!),
                )
        commands.forEach {
            assertEquals(CommandRequest.Valid(it), CommandUri.parse(CommandUri.of(it)), "$it")
        }
    }

    @Test
    fun outOfRangeNumbersParseSoPlanCanNameTheRange() {
        listOf("jetcommand:temp/0", "jetcommand:fan/20", "jetcommand:runtime/0").forEach {
            assertIs<CommandRequest.OutOfRange>(CommandUri.parse(it), it)
        }
    }

    @Test
    fun malformedIsNull() {
        listOf(
                null,
                "",
                "jetcommand:",
                "http:mode/STANDBY",
                "mode/STANDBY",
                "jetcommand://mode/STANDBY",
                "jetcommand:mode",
                "jetcommand:mode/",
                "jetcommand:temp/warm",
                "jetcommand:fan/1.5",
                "jetcommand:program/0",
                "jetcommand:program/4",
                "jetcommand:mode/standby",
                "jetcommand:mode/WARP",
                // A program step only; no button sets it.
                "jetcommand:mode/WAIT",
                "jetcommand:program/1/2",
                "jetcommand:launch/1",
                // Factory reset, and a button Press doesn't send.
                "jetcommand:press/76",
                "jetcommand:press/255",
            )
            .forEach { assertNull(CommandUri.parse(it), it) }
    }

    @Test
    fun shortcutsXmlNamesTheIntendedCommands() {
        val intents =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(File("src/main/res/xml/shortcuts.xml"))
                .getElementsByTagName("shortcut")
                .let { list -> List(list.length) { list.item(it) as Element } }
                .associate { shortcut ->
                    val intent = shortcut.getElementsByTagName("intent").item(0) as Element
                    fun attr(name: String) = intent.getAttributeNS(ANDROID, name)
                    assertEquals(CommandUri.ACTION, attr("action"))
                    assertEquals(CommandActivity::class.java.packageName, attr("targetPackage"))
                    assertEquals(CommandActivity::class.java.name, attr("targetClass"))
                    shortcut.getAttributeNS(ANDROID, "shortcutId") to CommandUri.parse(attr("data"))
                }
        assertEquals(
            mapOf(
                    "off" to UserCommand.SetMode(SettableMode.STANDBY),
                    "program1" to UserCommand.StartProgram(Slot.of(0)!!),
                    "program2" to UserCommand.StartProgram(Slot.of(1)!!),
                    "program3" to UserCommand.StartProgram(Slot.of(2)!!),
                )
                .mapValues { CommandRequest.Valid(it.value) },
            intents,
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
