package link.thesymbolic.jetcommand

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import link.thesymbolic.jetcommand.protocol.Mode
import link.thesymbolic.jetcommand.protocol.Slot
import link.thesymbolic.jetcommand.protocol.UserCommand
import org.w3c.dom.Element

class CommandUriTest {
    @Test
    fun everyCommandRoundTrips() {
        val commands =
            Mode.entries.map(UserCommand::SetMode) +
                Slot.all.map(UserCommand::StartProgram) +
                listOf(
                    UserCommand.SetTemperature(0),
                    UserCommand.SetTemperature(74),
                    UserCommand.SetFan(0),
                    UserCommand.SetFan(19),
                    UserCommand.Press(1),
                    UserCommand.Press(255),
                    UserCommand.SetRuntime(1),
                    UserCommand.SetRuntime(720),
                )
        commands.forEach { assertEquals(it, CommandUri.parse(CommandUri.of(it)), it.toString()) }
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
                "jetcommand:program/1/2",
                "jetcommand:launch/1",
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
                "off" to UserCommand.SetMode(Mode.STANDBY),
                "program1" to UserCommand.StartProgram(Slot.of(0)!!),
                "program2" to UserCommand.StartProgram(Slot.of(1)!!),
                "program3" to UserCommand.StartProgram(Slot.of(2)!!),
            ),
            intents,
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
